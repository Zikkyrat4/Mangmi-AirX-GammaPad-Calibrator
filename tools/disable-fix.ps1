$adb = Join-Path $PSScriptRoot 'adb.exe'
if (!(Test-Path $adb)) { $adb = Join-Path (Get-Location).Path 'adb.exe' }
if (!(Test-Path $adb)) { throw 'Run from folder containing adb.exe' }
& $adb shell su -c 'touch /data/adb/modules/gammapad_airx_fix/disable'
if ($LASTEXITCODE -ne 0) { throw 'Could not disable Magisk module' }
Write-Host 'Модуль помечен как отключённый. Сейчас консоль перезагрузится.' -ForegroundColor Yellow
& $adb reboot
