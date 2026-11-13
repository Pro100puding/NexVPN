# NexVPN (Android)

**Based on NexVPN by VikNex** · Лицензия: [MIT](LICENSE) — форкать и менять можно, копирайт сохранять обязаны.

Чёрно-фиолетовый VPN-клиент на публичных vless/reality-ключах.

## Что умеет
- Тянет ключи из публичных агрегаторов, парсит `vless://` (tcp/ws/xhttp/grpc), дедуп.
- Подбор живого ключа пробой на уровне транспорта (TLS/WS/REALITY), режим **«Выбирать лучшие по пингу»** — меряет отклик кандидатов и берёт быстрейший.
- **Ping** текущего подключения, обновляется каждые 5 секунд.
- **Встроенный DoH** (Cloudflare + Google fallback) и жёсткий рез HTTP (порт 80).
- Страна (флаг), протокол (REALITY/TLS/WS/XHTTP/GRPC), свитч «Обход белых списков».
- **Автообновление через GitHub Releases**: проверка раз в 10 минут, скачивание APK и установка по кнопке.

## Сборка
1. Android Studio → Open → папка `nexvpn` (или `gradlew assembleDebug`).
2. AAR движка лежит в `app/libs/libv2ray.aar` (уже в репозитории).
3. APK: `app/build/outputs/apk/`.

## Релизы (как выкатить новую версию)
1. Подними `versionCode` и `versionName` в `app/build.gradle`.
2. Заполни `keystore.properties` (лежит в корне, **в .gitignore** — в репозиторий не попадает):
   - `storeFile/storePassword/keyAlias/keyPassword` — подпись APK (см. ниже);
   - `githubToken` — PAT с правом Contents: Read and write на твой репозиторий
     (GitHub → Settings → Developer settings → Personal access tokens).
3. Пропиши свой репозиторий в `app/src/main/java/com/nex/vpn/UpdateChecker.kt`:
   `const val GITHUB_REPO = "owner/repo"` (пока `OWNER/REPO` — проверка выключена).
4. Запусти `release.cmd` — соберёт, подпишет APK и опубликует GitHub Release с тегом `v{versionName}`.
5. Приложения на телефонах сами увидят релиз и предложат обновиться.

### Подпись APK (создаётся один раз)
```
keytool -genkeypair -v -keystore keystore\nexvpn-release.jks -alias nexvpn ^
  -keyalg RSA -keysize 2048 -validity 10000 -storepass <пароль> -keypass <пароль> ^
  -dname "CN=NexVPN, O=VikNex, C=RU"
```
Пароли и alias вписать в `keystore.properties`. **Потеряешь keystore — не сможешь выкатывать обновления** (подпись не совпадёт), храни бэкап офлайн.

> Первую release-сборку надо установить поверх debug-версии через «Удалить и переустановить» — подписи разные.

## Иконки
Пак нарезан из исходника 1254×1254: mipmap 48–192, круглые, adaptive (v26), Play Store 512.
Пересоздать: `python make_icons.py`.
