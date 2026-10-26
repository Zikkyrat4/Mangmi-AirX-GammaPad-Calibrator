# Calibrate stock AIR X physical axes before installing the Magisk test module.
# Run from PowerShell on Windows while USB debug and Magisk root are available.
# This does NOT change vendor files or stock CALIBRATION_DATA.
# -UploadOnly reuses the existing RANGE_CALIBRATION without new measurements.
param([switch]$UploadOnly)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $PSScriptRoot 'adb.exe'
if (!(Test-Path $adb)) {
    $adb = Join-Path (Get-Location).Path 'adb.exe'
}
if (!(Test-Path $adb)) { throw 'Place this file beside adb.exe in platform-tools.' }

$devices = @(& $adb devices | Select-String '\sdevice$')
if ($devices.Count -lt 1) { throw 'ADB device missing. Confirm USB Debugging and permissions.' }
# Probe only live input descriptors. A missing FD must not become a terminating
# NativeCommandError in Windows PowerShell 5.1. GammaPad may change PID/FD.
function Get-PhysicalControllerFd {
    for ($attempt = 1; $attempt -le 5; $attempt++) {
        $pidLine = ((& $adb shell su -c 'pidof gammapad 2>/dev/null') -join ' ').Trim()
        if ($pidLine) {
            $gammaPid = ($pidLine -split '\s+')[0]
            $listing = @(& $adb shell su -c "ls -l /proc/$gammaPid/fd 2>/dev/null")
            $candidates = @()
            foreach ($line in $listing) {
                if ($line -match '\s(\d+)\s+->\s+/dev/input/event\d+') {
                    $candidates += [int]$Matches[1]
                }
            }
            # Fallback when /proc/<pid>/fd listing has an unexpected format.
            if ($candidates.Count -eq 0) { $candidates = @(3..20) }
            foreach ($n in ($candidates | Sort-Object -Unique)) {
                $candidate = "/proc/$gammaPid/fd/$n"
                # stderr is redirected on ANDROID, not by PowerShell, so a stale
                # descriptor cannot abort the whole script.
                $raw = @(& $adb shell su -c "getevent -lp $candidate 2>/dev/null")
                if ($LASTEXITCODE -eq 0 -and (($raw -join "`n") -match 'name:\s*"AIR X Controller"')) {
                    return $candidate
                }
            }
        }
        Start-Sleep -Milliseconds 500
    }
    throw 'Физический AIR X Controller не найден. GammaPad мог перезапуститься. Перезагрузи Mangmi и повтори попытку; ничего не изменено.'
}
if (-not $UploadOnly) {
    $fd = Get-PhysicalControllerFd
    Write-Host "Physical controller: $fd" -ForegroundColor Green
}
$axes = @('ABS_X','ABS_Y','ABS_Z','ABS_RZ')
function Snapshot {
    $output = @(& $adb shell su -c "getevent -lp $fd 2>/dev/null")
    if ($LASTEXITCODE -ne 0 -or (($output -join "`n") -notmatch 'name:\s*"AIR X Controller"')) {
        throw 'GammaPad изменил PID/FD во время измерения. Ничего не записано. Запусти скрипт снова.'
    }
    $result = @{}
    foreach ($name in $axes) {
        # Android getevent -lp puts the first ABS_X on the same line as
        # "ABS (0003):". Match the axis label anywhere on that line.
        $pattern = '(?<![A-Za-z0-9_])' + [regex]::Escape($name) + '\s*:\s*value\s+(-?\d+)'
        $found = $false
        foreach ($line in $output) {
            if ($line -match $pattern) {
                $result[$name] = [int]$Matches[1]
                $found = $true
                break
            }
        }
        if (-not $found) {
            throw "Не удалось прочитать ось $name. Пример ответа: $($output | Select-Object -First 7 | Out-String)"
        }
    }
    return $result
}
function SampleCenter {
    Write-Host "`nОТПУСТИ ОБА СТИКА. Нажми Enter." -ForegroundColor Cyan
    Read-Host | Out-Null
    $samples = @{}
    foreach ($a in $axes) { $samples[$a] = @() }
    for ($i=0;$i -lt 7;$i++) {
        $snap = Snapshot
        foreach ($a in $axes) { $samples[$a] += [int]$snap[$a] }
        Start-Sleep -Milliseconds 130
    }
    $centers = @{}
    foreach ($a in $axes) {
        $sorted = @($samples[$a] | Sort-Object)
        $centers[$a] = [int]$sorted[3]
    }
    return $centers
}
function ScanRange([string]$prompt, [string[]]$names) {
    Write-Host "`n$prompt" -ForegroundColor Cyan
    Read-Host 'Нажми Enter, чтобы начать' | Out-Null
    $min = @{}; $max = @{}
    foreach ($a in $names) { $min[$a] = 32767; $max[$a] = -32768 }
    Write-Host 'Води стик медленно по ВНЕШНЕМУ КРАЮ несколько полных оборотов...' -ForegroundColor Yellow
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $count = 0
    while ($watch.Elapsed.TotalSeconds -lt 18) {
        $sample = Snapshot
        foreach ($a in $names) {
            $v = [int]$sample[$a]
            if ($v -lt $min[$a]) { $min[$a] = $v }
            if ($v -gt $max[$a]) { $max[$a] = $v }
        }
        $count++
        if (($count % 10) -eq 0) { Write-Host '.' -NoNewline }
    }
    Write-Host "  samples=$count"
    return @{ Min=$min; Max=$max; Count=$count }
}
$path = Join-Path $PSScriptRoot 'RANGE_CALIBRATION'
if (-not $UploadOnly) {
    $center = SampleCenter
    $left = ScanRange 'ЛЕВЫЙ стик: обводи по кругу до механического упора, НЕ трогая правый.' @('ABS_X','ABS_Y')
    $right = ScanRange 'ПРАВЫЙ стик: обводи по кругу до механического упора, НЕ трогая левый.' @('ABS_Z','ABS_RZ')
    $map = @(
      @{ Name='ABS_X'; Id=0; Data=$left },
      @{ Name='ABS_Y'; Id=1; Data=$left },
      @{ Name='ABS_Z'; Id=2; Data=$right },
      @{ Name='ABS_RZ';Id=5; Data=$right }
    )
    $rows = @()
    $errors = @()
    foreach ($m in $map) {
        $a=$m.Name
        $mn=[int]$m.Data.Min[$a]; $mx=[int]$m.Data.Max[$a]; $mid=[int]$center[$a]
        $neg=$mid-$mn; $pos=$mx-$mid
        if ($neg -lt 9000 -or $pos -lt 9000) {
            $errors += "${a}: недостаточный диапазон ($neg / $pos), повтори измерение"
        }
        if ($mn -lt -32768 -or $mx -gt 32767) {
            $errors += "${a}: значения за пределами -32768...32767"
        }
        $rows += [PSCustomObject]@{Axis=$a;Id=$m.Id;Min=$mn;Center=$mid;Max=$mx;NegativeSpan=$neg;PositiveSpan=$pos}
    }
    $rows | Format-Table -AutoSize
    if ($errors.Count -gt 0) { throw ($errors -join "`n") }
    $lines = @('# AIR X calibrated raw axis min center max; created by calibrate-airx.ps1')
    foreach ($row in $rows) { $lines += "$($row.Id) $($row.Min) $($row.Center) $($row.Max)" }
    [System.IO.File]::WriteAllLines($path, $lines, [System.Text.Encoding]::ASCII)
} else {
    Write-Host 'Использую существующий RANGE_CALIBRATION; измерения стиков пропущены.' -ForegroundColor Cyan
}
# Validate both freshly generated and reused calibration data before touching the device.
if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Файл калибровки не найден: $path" }
$lines = @([System.IO.File]::ReadAllLines($path, [System.Text.Encoding]::ASCII))
$ids = @{}
foreach ($line in $lines) {
    if ($line.Trim().Length -eq 0 -or $line.TrimStart().StartsWith('#')) { continue }
    if ($line -notmatch '^([0125])\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s*$') {
        throw "Неверная строка калибровки: $line"
    }
    $id = [int]$Matches[1]
    if ($ids.ContainsKey($id)) { throw "Повторяется ось $id" }
    $mn = [int]$Matches[2]; $mid = [int]$Matches[3]; $mx = [int]$Matches[4]
    if ($mn -lt -32768 -or $mx -gt 32767 -or ($mid-$mn) -lt 9000 -or ($mx-$mid) -lt 9000) {
        throw "Некорректный диапазон для оси $id : $line"
    }
    $ids[$id] = $true
}
if ($ids.Count -ne 4 -or -not ($ids.ContainsKey(0) -and $ids.ContainsKey(1) -and $ids.ContainsKey(2) -and $ids.ContainsKey(5))) {
    throw 'Калибровка должна содержать ровно четыре оси: 0, 1, 2, 5.'
}
$backup = Join-Path $PSScriptRoot 'CALIBRATION_DATA.backup.txt'
$stock = @(& $adb shell su -c 'cat /data/GammaPad/CALIBRATION_DATA')
if ($LASTEXITCODE -ne 0 -or $stock.Count -lt 4) { throw 'Cannot back up stock calibration; no changes applied' }
[System.IO.File]::WriteAllLines($backup,[string[]]$stock,[System.Text.Encoding]::ASCII)
Write-Host "`nПредварительная калибровка рассчитана: $path" -ForegroundColor Green
Write-Host 'Она ещё НЕ применена. Нажми Enter, чтобы записать только новый RANGE_CALIBRATION.' -ForegroundColor Yellow
Read-Host | Out-Null
& $adb push $path /data/local/tmp/AIRX_RANGE_CALIBRATION | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Не удалось передать файл на консоль.' }
# Do not chmod: on some GammaOS builds chmod fails even when the file is
# correctly copied and already has readable (0644) permissions.
& $adb shell su -c 'cp /data/local/tmp/AIRX_RANGE_CALIBRATION /data/GammaPad/RANGE_CALIBRATION' | Out-Host
if ($LASTEXITCODE -ne 0) { throw 'Не удалось скопировать конфигурацию в /data/GammaPad/.' }
# Verify the exact bytes using SHA-256. Android's adb/su can return an unexpected
# number of PowerShell pipeline records for the same file, so a line-count check
# is not reliable on Windows PowerShell 5.1.
$localHash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToUpperInvariant()
$remoteHashOutput = @(& $adb shell su -c 'sha256sum /data/GammaPad/RANGE_CALIBRATION 2>/dev/null')
$hashCommandSucceeded = ($LASTEXITCODE -eq 0)
$remoteHashText = $remoteHashOutput -join "`n"
$remoteHash = $null
if ($hashCommandSucceeded -and $remoteHashText -match '(?i)\b([0-9a-f]{64})\b') {
    $remoteHash = $Matches[1].ToUpperInvariant()
}
if ($null -ne $remoteHash) {
    if ($remoteHash -cne $localHash) {
        throw "Ошибка проверки: SHA-256 на консоли ($remoteHash) отличается от локального ($localHash)."
    }
} else {
    # Fallback for builds without sha256sum: parse the four numeric records,
    # not the number of lines produced by the PowerShell native output adapter.
    $remoteOutput = @(& $adb shell su -c 'cat /data/GammaPad/RANGE_CALIBRATION 2>/dev/null')
    if ($LASTEXITCODE -ne 0) { throw 'Не удалось прочитать записанный RANGE_CALIBRATION.' }
    $remoteData = @{}
    $normalized = (($remoteOutput -join "`n") -replace "`r", '')
    foreach ($rawLine in ($normalized -split "`n")) {
        $line = $rawLine.Trim().TrimStart([char]0xFEFF)
        if (-not $line -or $line.StartsWith('#')) { continue }
        if ($line -notmatch '^([0125])\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s*$') {
            throw "В записанном файле найдена неверная строка: $line"
        }
        $id = [int]$Matches[1]
        if ($remoteData.ContainsKey($id)) { throw "В записанном файле ось $id встречается дважды." }
        $remoteData[$id] = "$([int]$Matches[2]) $([int]$Matches[3]) $([int]$Matches[4])"
    }
    if ($remoteData.Count -ne 4) { throw "Не удалось подтвердить запись: найдено $($remoteData.Count) из 4 осей." }
    foreach ($line in $lines) {
        if ($line.StartsWith('#') -or -not $line.Trim()) { continue }
        if ($line -notmatch '^([0125])\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s*$') {
            throw "Ошибка локального файла калибровки: $line"
        }
        $id = [int]$Matches[1]
        $expected = "$([int]$Matches[2]) $([int]$Matches[3]) $([int]$Matches[4])"
        if (-not $remoteData.ContainsKey($id) -or $remoteData[$id] -cne $expected) {
            throw "Записанные данные оси $id не совпадают с локальными."
        }
    }
}
Write-Host 'ГОТОВО: RANGE_CALIBRATION записан и проверен (SHA-256 или 4 числовые записи).' -ForegroundColor Green
Write-Host 'Штатный CALIBRATION_DATA не изменён. Новый диапазон применяется только совместимым модулем GammaPad.' -ForegroundColor Yellow
