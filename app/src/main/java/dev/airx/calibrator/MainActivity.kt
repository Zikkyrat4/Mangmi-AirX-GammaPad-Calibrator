package dev.airx.calibrator

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Prototype for a specific rooted Mangmi Air X / GammaOS Next installation.
 * IMPORTANT: values are read from RAW AIR X Controller, never the virtual Xbox pad.
 * Only /data/GammaPad/RANGE_CALIBRATION is overwritten after backup + confirmation.
 */
class MainActivity : Activity() {
    private data class Axis(val name: String, val id: Int)
    private val axes = listOf(Axis("ABS_X", 0), Axis("ABS_Y", 1), Axis("ABS_Z", 2), Axis("ABS_RZ", 5))
    private val work = Executors.newSingleThreadExecutor()
    private var busy = false
    private var checked = false
    private val prefs by lazy { getSharedPreferences("gamma_calibrator", MODE_PRIVATE) }
    private val configDir = "/data/GammaPad"
    private val sensePath = "$configDir/ANALOGSENSITIVITY"
    private val senseBackup = "$configDir/ANALOGSENSITIVITY.previous-app"
    private val rangePath = "$configDir/RANGE_CALIBRATION"
    private val rangeBackup = "$configDir/RANGE_CALIBRATION.previous-app"
    private var center: Map<String, Int>? = null
    private var leftRange: Map<String, Pair<Int, Int>>? = null
    private var rightRange: Map<String, Pair<Int, Int>>? = null

    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var progress: ProgressBar
    private lateinit var checkButton: Button
    private lateinit var centerButton: Button
    private lateinit var leftButton: Button
    private lateinit var rightButton: Button
    private lateinit var saveButton: Button
    private lateinit var zeroSensitivityButton: Button
    private lateinit var restoreSensitivityButton: Button
    private lateinit var restoreRangeButton: Button
    private lateinit var rebootButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(20), dp(22), dp(20))
        }
        val title = TextView(this).apply {
            text = "GammaPad Calibrator v0.4 · Mangmi Air X"
            textSize = 23f
            setTextColor(0xff192a3a.toInt())
        }
        root.addView(title)
        root.addView(TextView(this).apply {
            text = "Только для GammaOS Next + совместимого Magisk-модуля. Требуется Root."
            textSize = 14f
            setPadding(0, dp(8), 0, dp(14))
        })

        checkButton = button(root, "1. Проверить Root и модуль") { runCheck() }
        zeroSensitivityButton = button(root, "Дополнительно: сохранить чувствительность и установить 0") { confirmSetZero() }
        restoreSensitivityButton = button(root, "Вернуть прежнюю чувствительность") { confirmRestoreSensitivity() }
        centerButton = button(root, "2. Измерить центр (отпусти стики)") { measureCenter() }
        leftButton = button(root, "3. Левый стик: вращать 18 секунд") {
            confirmScan("левого") { scan(listOf("ABS_X", "ABS_Y"), true) }
        }
        rightButton = button(root, "4. Правый стик: вращать 18 секунд") {
            confirmScan("правого") { scan(listOf("ABS_Z", "ABS_RZ"), false) }
        }
        saveButton = button(root, "5. Сохранить калибровку") { confirmSave() }
        restoreRangeButton = button(root, "Восстановить предыдущую калибровку") { confirmRestoreRange() }
        rebootButton = button(root, "Перезагрузить консоль") { confirmReboot() }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        root.addView(progress, LinearLayout.LayoutParams(-1, dp(8)).apply {
            topMargin = dp(13); bottomMargin = dp(13)
        })
        status = TextView(this).apply { text = "Начни с проверки Root и модуля."; textSize = 16f }
        root.addView(status)
        details = TextView(this).apply {
            text = "Компьютер не требуется. Приложение работает с Magisk Root. При изменении чувствительности и калибровки сохраняет резервные копии. Штатный CALIBRATION_DATA не изменяется."
            textSize = 14f
            setPadding(0, dp(12), 0, dp(10))
        }
        root.addView(details)
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        refreshButtons()
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun button(parent: LinearLayout, title: String, fn: () -> Unit): Button = Button(this).also {
        it.text = title
        it.setAllCaps(false)
        it.setOnClickListener { _ -> fn() }
        parent.addView(it, LinearLayout.LayoutParams(-1, dp(57)))
    }
    private fun ui(block: () -> Unit) = runOnUiThread(block)
    private fun refreshButtons() {
        checkButton.isEnabled = !busy
        zeroSensitivityButton.isEnabled = !busy
        restoreSensitivityButton.isEnabled = !busy
        restoreRangeButton.isEnabled = !busy
        rebootButton.isEnabled = !busy
        centerButton.isEnabled = !busy && checked
        leftButton.isEnabled = !busy && center != null
        rightButton.isEnabled = !busy && leftRange != null
        saveButton.isEnabled = !busy && center != null && leftRange != null && rightRange != null
    }
    private fun task(title: String, fn: () -> Unit) {
        if (busy) return
        busy = true
        status.text = title
        refreshButtons()
        work.execute {
            try { fn() }
            catch (e: Exception) {
                ui {
                    val message = e.message ?: e.javaClass.simpleName
                    status.text = "Ошибка проверки контроллера. Подробности ниже."
                    details.text = message
                    progress.visibility = View.GONE
                }
            } finally { ui { busy = false; refreshButtons() } }
        }
    }

    /** Magisk prompts user to grant root the first time this runs. */
    private fun su(command: String, timeoutSeconds: Long = 25): String {
        val proc = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true).start()
        if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            proc.destroyForcibly()
            throw IllegalStateException("Root-команда превысила $timeoutSeconds с. Проверь разрешение Magisk.")
        }
        val output = proc.inputStream.bufferedReader().use { it.readText() }.trim()
        if (proc.exitValue() != 0) {
            throw IllegalStateException("Команда завершилась с кодом ${proc.exitValue()}: ${output.take(240)}")
        }
        return output
    }
    private fun shQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"

    private fun gammaPid(): Int {
        val output = su("pidof gammapad")
        return output.split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
            ?: throw IllegalStateException("Процесс gammapad не найден")
    }
    private fun requireRoot() {
        if (!su("id", 90).contains("uid=0")) {
            throw IllegalStateException("Нет Root. Разреши доступ в Magisk.")
        }
    }
    private fun readSensitivity(): String = su("cat ${shQuote(sensePath)}").trim()

    // Reboot requirement survives an Activity or app restart, but clears on a real device reboot.
    private fun rebootPending(): Boolean {
        val saved = prefs.getString("sensitivity_change_boot_id", null) ?: return false
        val bootId = su("cat /proc/sys/kernel/random/boot_id").trim()
        if (saved != bootId) {
            prefs.edit().remove("sensitivity_change_boot_id").apply()
            return false
        }
        return true
    }
    private fun markSensitivityChanged() {
        prefs.edit().putString("sensitivity_change_boot_id", su("cat /proc/sys/kernel/random/boot_id").trim()).apply()
    }

    private fun ensureCompatible(requireFreshBoot: Boolean = true): Int {
        requireRoot()
        val pid = gammaPid()
        val module = "/data/adb/modules/gammapad_airx_fix"
        val ok = su(
            "test -f $module/module.prop && " +
            "test ! -e $module/disable && " +
            "grep -q '/vendor/lib64/gpad.so' /proc/$pid/maps && echo READY"
        )
        if (!ok.contains("READY")) throw IllegalStateException("Библиотека исправления не загружена")
        // We sample the physical AIR X evdev node, not GammaPad's virtual output.
        // Verified earlier on this device: RANGE_CALIBRATION worked with sensitivity 3.
        // Sensitivity 0 is therefore NOT required just to collect raw samples.
        if (requireFreshBoot && rebootPending()) {
            throw IllegalStateException("После изменения чувствительности сначала перезагрузи консоль.")
        }
        return pid
    }
    /**
     * Physical AIR X device can be held open by GammaPad and absent in /dev/input.
     * v0.2 searched ONLY the parsed /proc/<pid>/fd links (or 3..20), which
     * incorrectly rejects other descriptor layouts. Probe all fd targets, with
     * a verified direct-device fallback. NEVER guess that an event belongs to
     * AIR X: getevent must confirm the device name before calibration.
     */
    private fun physicalFd(pid: Int): String {
        val probe = """
            for fd in /proc/$pid/fd/3 /proc/$pid/fd/[0-9]* /dev/input/event*; do
                result=${'$'}(/system/bin/getevent -lp "${'$'}fd" 2>&1)
                case "${'$'}result" in
                    *"AIR X Controller"*) printf 'AIRX_FOUND=%s\n' "${'$'}fd"; exit 0 ;;
                esac
            done
            printf 'AIRX_NOT_FOUND\n'
            printf 'FD_LIST\n'
            ls -l /proc/$pid/fd 2>&1 | head -n 22
            printf 'FD3_PROBE\n'
            /system/bin/getevent -lp /proc/$pid/fd/3 2>&1 | head -n 12
        """.trimIndent()
        val result = su(probe, 45)
        val found = Regex("(?m)^AIRX_FOUND=(/\\S+)$").find(result)?.groupValues?.get(1)
        if (found != null) return found
        throw IllegalStateException(
            "AIR X Controller не найден. Диагностика:\n" + result.takeLast(850)
        )
    }
    private fun snapshot(fd: String): Map<String, Int> {
        val out = su("/system/bin/getevent -lp $fd")
        if (!Regex("AIR\\s+X\\s+Controller", RegexOption.IGNORE_CASE).containsMatchIn(out)) {
            throw IllegalStateException("Исчез физический контроллер: GammaPad мог перезапуститься")
        }
        return axes.associate { axis ->
            val regex = Regex("""(?<![A-Za-z0-9_])${axis.name}\s*:\s*value\s+(-?\d+)""")
            axis.name to (regex.find(out)?.groupValues?.get(1)?.toIntOrNull()
                ?: throw IllegalStateException("Нет значения ${axis.name} в getevent"))
        }
    }
    private fun runCheck() = task("Проверяю Root, GammaPad и физический контроллер…") {
        ui { checked = false; center = null; leftRange = null; rightRange = null; refreshButtons() }
        val pid = ensureCompatible(requireFreshBoot = false)
        val fd = physicalFd(pid)
        val sensitivity = readSensitivity()
        val needsReboot = rebootPending()
        val range = try { su("cat ${shQuote(rangePath)}").take(560) }
                    catch (_: Exception) { "Файл RANGE_CALIBRATION не обнаружен" }
        ui {
            checked = !needsReboot && sensitivity.matches(Regex("\\d+"))
            status.text = when {
                needsReboot -> "Контроллер найден, но после изменения чувствительности нужна перезагрузка."
                !checked -> "Не удалось прочитать числовое значение чувствительности: $sensitivity"
                else -> "Готово: Root, модуль и сырой контроллер найдены. Чувствительность $sensitivity допустима для измерения сырых осей. Можно измерять центр."
            }
            details.text = "Сырой контроллер: $fd\nANALOGSENSITIVITY=$sensitivity\n\nТекущая калибровка:\n$range"
        }
    }

    private fun confirmSetZero() {
        AlertDialog.Builder(this)
            .setTitle("Установить чувствительность 0?")
            .setMessage("Значение 0 НЕ требуется для калибровки сырых осей: твой исправленный GammaPad уже работал при значении 3. Изменение чувствительности может повлиять на управление. Если всё же продолжить, приложение сохранит прежнее значение и установит 0. Для применения потребуется перезагрузка. CALIBRATION_DATA не изменяется.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить и установить 0") { _, _ -> setSensitivityZero() }
            .show()
    }
    private fun setSensitivityZero() = task("Сохраняю чувствительность и устанавливаю 0…") {
        requireRoot()
        val previous = readSensitivity()
        require(previous.matches(Regex("\\d+"))) { "Некорректное значение ANALOGSENSITIVITY: $previous" }
        if (previous == "0") {
            ui { status.text = "Чувствительность уже равна 0. Системный файл не изменён." }
            return@task
        }
        val alreadyBackup = su("if [ -f ${shQuote(senseBackup)} ]; then echo YES; else echo NO; fi") == "YES"
        if (!alreadyBackup) {
            su("cp ${shQuote(sensePath)} ${shQuote(senseBackup)}")
            require(su("cat ${shQuote(senseBackup)}").trim() == previous) { "Не удалось проверить резервную копию. Изменение отменено." }
        } else {
            val saved = su("cat ${shQuote(senseBackup)}").trim()
            require(saved.matches(Regex("\\d+"))) { "Существующая резервная копия чувствительности повреждена. Изменение отменено." }
        }
        val stage = "/data/local/tmp/AIRX_SENSITIVITY_NEW"
        su("printf '0\\n' > ${shQuote(stage)}")
        su("cp ${shQuote(stage)} ${shQuote(sensePath)}")
        require(readSensitivity() == "0") { "Не удалось подтвердить запись чувствительности 0" }
        markSensitivityChanged()
        ui {
            checked = false
            center = null; leftRange = null; rightRange = null
            status.text = "Чувствительность установлена в 0. Теперь перезагрузи Mangmi кнопкой ниже."
            details.text = "До изменения: $previous\nРезервная копия: $senseBackup\nПосле перезагрузки нажми «Проверить Root и модуль»."
        }
    }
    private fun confirmRestoreSensitivity() {
        AlertDialog.Builder(this)
            .setTitle("Вернуть прежнюю чувствительность?")
            .setMessage("Будет восстановлено значение из $senseBackup. Текущая калибровка диапазонов останется без изменений. Для применения понадобится перезагрузка.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Восстановить") { _, _ -> restoreSensitivity() }.show()
    }
    private fun restoreSensitivity() = task("Восстанавливаю предыдущую чувствительность…") {
        requireRoot()
        val expected = su("cat ${shQuote(senseBackup)}").trim()
        require(expected.matches(Regex("\\d+"))) { "Резервная копия содержит неверное значение" }
        su("cp ${shQuote(senseBackup)} ${shQuote(sensePath)}")
        require(readSensitivity() == expected) { "Не удалось проверить восстановленное значение" }
        markSensitivityChanged()
        ui {
            checked = false
            center = null; leftRange = null; rightRange = null
            status.text = "Восстановлена чувствительность $expected. Требуется перезагрузка."
            details.text = "Значение взято из $senseBackup. После перезагрузки калибровать можно при любом корректном значении чувствительности."
        }
    }

    private fun measureCenter() = task("Отпусти ОБА стика. Измеряю центр…") {
        val fd = physicalFd(ensureCompatible())
        val samples = axes.associate { it.name to mutableListOf<Int>() }
        repeat(7) {
            val snap = snapshot(fd)
            axes.forEach { samples.getValue(it.name).add(snap.getValue(it.name)) }
            Thread.sleep(130)
        }
        val result = axes.associate { a -> a.name to samples.getValue(a.name).sorted()[3] }
        ui {
            center = result
            leftRange = null
            rightRange = null
            status.text = "Центр измерен. Теперь вращай левый стик."
            details.text = result.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        }
    }
    private fun confirmScan(which: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setTitle("Калибровка $which стика")
            .setMessage("После нажатия «Начать» медленно вращай стик по ВНЕШНЕМУ краю 18 секунд, не трогая другой стик.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Начать") { _, _ -> yes() }.show()
    }
    private fun scan(names: List<String>, isLeft: Boolean) = task("Вращай стик по внешнему краю…") {
        val fd = physicalFd(ensureCompatible())
        val lo = names.associateWith { 32767 }.toMutableMap()
        val hi = names.associateWith { -32768 }.toMutableMap()
        val start = SystemClock.elapsedRealtime()
        ui { progress.visibility = View.VISIBLE; progress.progress = 0 }
        var n = 0
        while (SystemClock.elapsedRealtime() - start < 18_000L) {
            val snap = snapshot(fd)
            names.forEach { name ->
                val v = snap.getValue(name)
                lo[name] = min(lo.getValue(name), v)
                hi[name] = max(hi.getValue(name), v)
            }
            n++
            val percent = (((SystemClock.elapsedRealtime() - start) * 100) / 18_000).toInt().coerceIn(0, 100)
            ui { progress.progress = percent; status.text = "Вращай стик: $percent% ($n измерений)" }
            Thread.sleep(110)
        }
        val result = names.associateWith { lo.getValue(it) to hi.getValue(it) }
        ui {
            if (isLeft) { leftRange = result; rightRange = null } else rightRange = result
            progress.visibility = View.GONE
            status.text = if (isLeft) "Левый готов. Теперь измерь правый." else "Оба измерены. Проверь значения и нажми «Сохранить»."
            details.text = result.entries.joinToString("\n") { "${it.key}: ${it.value.first} … ${it.value.second}" }
        }
    }
    private fun buildData(): String {
        val centers = center ?: error("Центр не измерен")
        val left = leftRange ?: error("Левый стик не измерен")
        val right = rightRange ?: error("Правый стик не измерен")
        val rows = axes.map { a ->
            val (low, high) = (if (a.id == 0 || a.id == 1) left else right).getValue(a.name)
            val mid = centers.getValue(a.name)
            require(low >= -32768 && high <= 32767 && low < mid && mid < high) { "Некорректная ось ${a.name}" }
            require(mid - low >= 9000 && high - mid >= 9000) { "Недостаточный размах оси ${a.name}: повтори калибровку" }
            "${a.id} $low $mid $high"
        }
        return (listOf("# AIR X calibrated raw axis min center max; created by GammaPad Calibrator") + rows).joinToString("\n", postfix = "\n")
    }
    private fun confirmSave() {
        val result = try { buildData() } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage(e.message).setPositiveButton("OK", null).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Сохранить калибровку?")
            .setMessage("Будет сохранена резервная копия текущего RANGE_CALIBRATION, затем записан новый файл:\n\n$result\nШтатный CALIBRATION_DATA не затрагивается. Для применения потребуется перезагрузка.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ -> save(result) }.show()
    }
    private fun sha256(input: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(input).joinToString("") { "%02x".format(it) }
    private fun remoteSha(path: String): String {
        val output = su("sha256sum ${shQuote(path)}")
        return Regex("(?i)[a-f0-9]{64}").find(output)?.value?.lowercase()
            ?: error("Нет SHA-256 для файла $path")
    }

    private fun save(content: String) = task("Создаю резервную копию и записываю RANGE_CALIBRATION…") {
        ensureCompatible()
        val stage = "/data/local/tmp/AIRX_RANGE_CALIBRATION"
        val rows = content.trimEnd('\n').split('\n')
        su("printf '%s\\n' " + rows.joinToString(" ") { shQuote(it) } + " > ${shQuote(stage)}")
        val expected = sha256(content.toByteArray(Charsets.US_ASCII))
        require(remoteSha(stage) == expected) { "Ошибка проверки временного файла, запись отменена" }
        val oldExists = su("if [ -f ${shQuote(rangePath)} ]; then echo YES; else echo NO; fi") == "YES"
        if (oldExists) {
            val originalBackup = "$configDir/RANGE_CALIBRATION.original-app"
            if (su("if [ -f ${shQuote(originalBackup)} ]; then echo YES; else echo NO; fi") == "NO") {
                su("cp ${shQuote(rangePath)} ${shQuote(originalBackup)}")
                require(remoteSha(originalBackup) == remoteSha(rangePath)) { "Не удалось проверить исходную резервную копию" }
            }
            val before = remoteSha(rangePath)
            su("cp ${shQuote(rangePath)} ${shQuote(rangeBackup)}")
            require(remoteSha(rangeBackup) == before) { "Не удалось создать предыдущую резервную копию" }
        }
        su("cp ${shQuote(stage)} ${shQuote(rangePath)}")
        if (remoteSha(rangePath) != expected) {
            if (oldExists) su("cp ${shQuote(rangeBackup)} ${shQuote(rangePath)}")
            throw IllegalStateException("Проверка записи не прошла. Предыдущая калибровка восстановлена, если имелась.")
        }
        ui {
            status.text = "УСПЕХ: новая калибровка записана и проверена SHA-256."
            details.text = "Предыдущая версия: $rangeBackup\nИсходная версия: $configDir/RANGE_CALIBRATION.original-app\nТеперь перезагрузи Mangmi, затем проверь стики. CALIBRATION_DATA не изменён."
        }
    }

    private fun validateRange(content: String) {
        val ids = mutableSetOf<Int>()
        for (line in content.lineSequence().map { it.trim() }) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val pieces = line.split(Regex("\\s+"))
            require(pieces.size == 4) { "Неверный формат резервной копии" }
            val parsed = pieces.map { it.toIntOrNull() ?: error("Нечисловые данные в копии") }
            val (id, lo, mid, hi) = parsed
            require(id in listOf(0, 1, 2, 5) && ids.add(id)) { "Неизвестная или повторная ось $id" }
            require(lo >= -32768 && hi <= 32767 && lo < mid && mid < hi && mid-lo >= 9000 && hi-mid >= 9000) {
                "Недопустимый диапазон в копии для оси $id"
            }
        }
        require(ids == setOf(0, 1, 2, 5)) { "В копии должны быть четыре оси 0, 1, 2, 5" }
    }
    private fun confirmRestoreRange() {
        AlertDialog.Builder(this).setTitle("Восстановить предыдущую калибровку?")
            .setMessage("Приложение проверит $rangeBackup, создаст копию текущего файла и восстановит предыдущие значения. После потребуется перезагрузка. Штатный CALIBRATION_DATA не меняется.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Восстановить") { _, _ -> restoreRange() }.show()
    }
    private fun restoreRange() = task("Восстанавливаю RANGE_CALIBRATION…") {
        requireRoot()
        val backupContent = su("cat ${shQuote(rangeBackup)}")
        validateRange(backupContent)
        val expected = remoteSha(rangeBackup)
        val beforeRestore = "$configDir/RANGE_CALIBRATION.before-restore-app"
        if (su("if [ -f ${shQuote(rangePath)} ]; then echo YES; else echo NO; fi") == "YES") {
            val oldHash = remoteSha(rangePath)
            su("cp ${shQuote(rangePath)} ${shQuote(beforeRestore)}")
            require(remoteSha(beforeRestore) == oldHash) { "Не удалось создать копию перед восстановлением" }
        }
        su("cp ${shQuote(rangeBackup)} ${shQuote(rangePath)}")
        require(remoteSha(rangePath) == expected) { "Восстановление не прошло проверку SHA-256" }
        ui {
            checked = false; center = null; leftRange = null; rightRange = null
            status.text = "Предыдущая калибровка восстановлена. Перезагрузи консоль."
            details.text = "Копия конфигурации перед восстановлением: $beforeRestore\nШтатный CALIBRATION_DATA не изменён."
        }
    }
    private fun confirmReboot() {
        AlertDialog.Builder(this).setTitle("Перезагрузить Mangmi?")
            .setMessage("Сохрани прогресс в играх. Консоль перезагрузится, и GammaPad заново прочитает калибровку и чувствительность.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Перезагрузить") { _, _ -> rebootConsole() }.show()
    }
    private fun rebootConsole() = task("Отправляю команду перезагрузки…") {
        requireRoot()
        // Device goes offline immediately, so reboot must not be awaited with su().
        ProcessBuilder("su", "-c", "reboot").start()
        ui { status.text = "Команда перезагрузки отправлена." }
    }

    override fun onDestroy() {
        work.shutdownNow()
        super.onDestroy()
    }
}
