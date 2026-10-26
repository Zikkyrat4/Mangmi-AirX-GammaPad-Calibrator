# Публикация в GitHub: пошагово (Windows)

## 1. Создать репозиторий

- На https://github.com/new создать **пустой** репозиторий `Mangmi-AirX-GammaPad-Calibrator` (не отмечать README, license, .gitignore).
- В PowerShell перейти в папку `repository`, извлечённую из этого комплекта.
- Проверить `git --version`; если Git не установлен — установить Git for Windows с https://git-scm.com/downloads/win.

```powershell
git init
git branch -M main
git add .
git status
git commit -m "Initial release: Android calibrator v0.4 and Magisk patch source"
git remote add origin https://github.com/USERNAME/Mangmi-AirX-GammaPad-Calibrator.git
git push -u origin main
```

Заменить USERNAME. При входе используйте браузер/менеджер учётных данных Git — не публикуйте пароль/токен.

## 2. Подготовить два установочных файла

- **APK:** скопировать из своего уже собранного Android Studio-проекта `app/build/outputs/apk/debug/app-debug.apk` в папку `release-assets`, переименовать в `GammaPad-Calibrator-v0.4.apk`.
- **ZIP:** в папке `release-assets` уже лежит проверяемый экспериментальный модуль `GammaPad-AirX-Fix-v0.1.0.zip`. **Он содержит модифицированный ELF из прошивки. Не публиковать публично, пока не подтверждены права на распространение!**
- Эти файлы **не коммитятся в Git**: папка `release-assets/` игнорируется в `.gitignore`.

## 3. Создать релиз

- Открыть `https://github.com/USERNAME/Mangmi-AirX-GammaPad-Calibrator/releases`.
- `Draft a new release`, новый тег `v0.4.0`, заголовок `GammaPad Calibrator v0.4 (experimental)`.
- Указать поддерживаемую прошивку и инструкции по откату.
- Перетащить **APK** и, при подтверждённых правах, **ZIP** в `Attach binaries`.
- Выбрать **Set as a pre-release**, затем `Publish release`.
- Для приватного тестирования сначала используйте **private** репозиторий.

## Обновления

При обновлении Android-приложения повышать и `versionCode`, и `versionName` в `app/build.gradle.kts`.
Для пользовательских релизов подписывать APK стабильным ключом (не коммитить его в репозиторий).
