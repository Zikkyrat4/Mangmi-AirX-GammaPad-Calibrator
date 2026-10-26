# GammaPad Calibrator — Mangmi Air X

[![Version](https://img.shields.io/badge/version-v0.4.0--beta.1-orange)](https://github.com/Zikkyrat4/Mangmi-AirX-GammaPad-Calibrator/releases/tag/v0.4.0-beta.1)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Root required](https://img.shields.io/badge/root-Magisk-required-red)
![Status](https://img.shields.io/badge/status-experimental-yellow)

**Неофициальное приложение для калибровки аналоговых стиков Mangmi Air X на GammaOS Next 1.2.** Проект состоит из Android-приложения и экспериментального Magisk-модуля коррекции диапазонов GammaPad.

*Unofficial stick calibration app and experimental Magisk axis-range fix for Mangmi Air X running GammaOS Next 1.2. Root required. Other devices and firmware versions are not verified.*

**[Скачать первую бета-версию (Releases)](https://github.com/Zikkyrat4/Mangmi-AirX-GammaPad-Calibrator/releases/tag/v0.4.0-beta.1)** · [Исходники приложения](app/) · [Исходники исправления](magisk-module/source/) · [История версий](CHANGELOG.md)

> **Экспериментальный проект.** Проверялся в рамках настройки конкретной Mangmi Air X. Не является официальным продуктом Mangmi, GammaOS или Magisk. Изменение системного обработчика ввода через Root потенциально нарушает работу контроллера. Перед установкой сохраните возможность отката через ADB.

## Возможности

| Компонент | Назначение |
| --- | --- |
| **GammaPad Calibrator (APK)** | Проверка Root и GammaPad, поиск физического `AIR X Controller`, измерение центра и диапазонов обоих стиков, сохранение и восстановление калибровки |
| **GammaPad AirX Fix (Magisk)** | Экспериментальное преобразование сырых показаний осей на основании `/data/GammaPad/RANGE_CALIBRATION` |
| **PowerShell-скрипт** | Альтернативная калибровка с компьютера через ADB |

Приложение считывает **физический** контроллер, а не виртуальный Xbox-геймпад. Измеряемые оси: `ABS_X`, `ABS_Y`, `ABS_Z`, `ABS_RZ`. В версии v0.4 значение `ANALOGSENSITIVITY=3` не блокирует измерения сырых осей.

## Совместимость

| Параметр | Требование / состояние |
| --- | --- |
| Устройство | **Mangmi Air X** |
| Прошивка | **GammaOS Next 1.2** |
| Root | **Magisk**, обязательно |
| Android-приложение | Минимальный SDK 29 (Android 10) |
| Magisk-модуль | Совместимость **не универсальная**; выполняет проверки образа и исходного бинарного файла |
| Другие прошивки и устройства | **Не проверены** |

Режим работы рассчитан на штатный процесс `/vendor/bin/gammapad`, физическое устройство `AIR X Controller` и каталог `/data/GammaPad/`. Совпадение версии Android само по себе **не означает**, что модуль совместим.

## Загрузка

Откройте **[Releases → v0.4.0-beta.1](https://github.com/Zikkyrat4/Mangmi-AirX-GammaPad-Calibrator/releases/tag/v0.4.0-beta.1)**.

- `GammaPad-Calibrator-v0.4-beta1.apk` — приложение Android (выпущено как **debug APK**, подпись не является постоянной релизной).
- `GammaPad-AirX-Fix-v0.1.0.zip` — экспериментальный Magisk-модуль **только для поддерживаемой конфигурации**.

**Важно о распространении:** ZIP Magisk-модуля содержит изменённый `gammapad`, полученный из прошивки устройства. Право на распространение этого бинарного файла ещё не подтверждено. Перед дальнейшим распространением проверьте условия лицензии; предпочтительна будущая реализация установщика, который изменяет оригинальный бинарный файл непосредственно на устройстве пользователя. Такой установщик пока **не создан**.

## Установка и калибровка

1. Убедитесь, что Root через Magisk работает и доступен способ восстановления через ADB.
2. **Только при подтверждённой совместимости** установите Magisk ZIP: **Magisk → Modules → Install from storage**. Перезагрузите консоль.
3. Установите APK обычным способом, откройте **GammaPad Calibrator** и разрешите Root.
4. Нажмите **«Проверить Root и модуль»**. Убедитесь, что приложение нашло **сырой AIR X Controller**.
5. Отпустите оба стика и измерьте центр.
6. По приглашению приложения медленно обведите левый, затем правый стик по механическому упору (по **18 секунд**).
7. Проверьте значения, сохраните калибровку с резервной копией и перезагрузите устройство.
8. Проверьте результат в Gamepad Tester: четыре направления, диагонали и возврат в центр.

Если калибровка **уже устраивает**, нет необходимости перезаписывать её. Приложение работает с `RANGE_CALIBRATION`; штатный `CALIBRATION_DATA` не должен изменяться.

## Аварийное отключение модуля

При нарушении работы стиков подключите консоль по ADB и выполните:

```powershell
.\adb.exe shell su -c "touch /data/adb/modules/gammapad_airx_fix/disable"
.\adb.exe reboot
```

Команды предполагают, что Root через ADB доступен. Не устанавливайте экспериментальный модуль без плана восстановления.

## Исходный код и сборка

```text
app/                 Kotlin-приложение (v0.4)
magisk-module/       Скрипты Magisk и код перехватчика gpad.c
tools/               PowerShell-калибровка и откат
CHANGELOG.md         История версий
```

Для приложения используются **Android Studio**, **JDK 21**, **Android SDK 35**, **Android Gradle Plugin 8.13.2** и **Gradle 8.13**. Откройте корень репозитория как проект Android Studio, при необходимости настройте Gradle Wrapper, затем выберите **Build → Build APK(s)**. Ожидаемый путь: `app/build/outputs/apk/debug/app-debug.apk`.

В текущем репозитории **нет** настроенного workflow автоматической сборки GitHub Actions и **нет** постоянного ключа подписи для релизных APK. Не добавляйте `.jks`/`.keystore` и пароли в Git.

## Обратная связь

Нашли ошибку? Создайте [Issue](https://github.com/Zikkyrat4/Mangmi-AirX-GammaPad-Calibrator/issues), указав версию прошивки, устройства и приложения, последовательность действий и текст ошибки. **Не публикуйте пароли, ключи и личные данные.**

## Лицензирование

Лицензия на собственные исходники пока не выбрана. Права на исходный бинарный `gammapad` из прошивки необходимо проверять отдельно; размещение исходников в публичном репозитории само по себе не предоставляет разрешение на их дальнейшее распространение.
