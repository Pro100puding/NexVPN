# Релиз NexVPN: сборка -> подпись -> публикация GitHub Release с APK.
# Запуск: release.cmd (или powershell -File release.ps1)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot

function Fail($msg) { Write-Host "ОШИБКА: $msg" -ForegroundColor Red; exit 1 }

# --- 1. Версия из app/build.gradle ---
$gradleText = Get-Content "$root\app\build.gradle" -Raw
if ($gradleText -notmatch 'versionName\s+"([^"]+)"') { Fail "не нашёл versionName в app/build.gradle" }
$version = $Matches[1]
Write-Host "Версия: $version" -ForegroundColor Cyan

# --- 2. Секреты ---
$propsFile = "$root\keystore.properties"
if (-not (Test-Path $propsFile)) { Fail "нет keystore.properties (создай по README)" }
$props = @{}
Get-Content $propsFile | ForEach-Object {
    $line = $_.Trim()
    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $i = $line.IndexOf("=")
        $props[$line.Substring(0, $i).Trim()] = $line.Substring($i + 1).Trim()
    }
}
foreach ($k in "storeFile", "storePassword", "keyAlias", "keyPassword") {
    if (-not $props[$k]) { Fail "в keystore.properties нет $k" }
}

# --- 3. Сборка release (апк выходит уже подписанным, если задан signingConfig) ---
$gradle = if (Test-Path "$root\gradlew.bat") { "$root\gradlew.bat" }
          elseif (Test-Path "C:\gradle-8.13\bin\gradle.bat") { "C:\gradle-8.13\bin\gradle.bat" }
          else { "gradle" }
Write-Host "Сборка..." -ForegroundColor Cyan
& $gradle -p $root assembleRelease --console=plain
if ($LASTEXITCODE -ne 0) { Fail "gradle assembleRelease упал (см. вывод выше)" }

$apk = "$root\app\build\outputs\apk\release\app-release.apk"
$apkUnsigned = "$root\app\build\outputs\apk\release\app-release-unsigned.apk"
if (Test-Path $apk) {
    # подпись уже применена gradle signingConfig
    Write-Host "APK подписан: $apk" -ForegroundColor Green
} elseif (Test-Path $apkUnsigned) {
    # fallback: подписать вручную через apksigner
    $sdk = ((Get-Content "$root\local.properties") | Where-Object { $_ -like "sdk.dir*" }) -replace "^sdk.dir=", ""
    $sdk = $sdk.Trim().Replace('\\', '\')
    if (-not $sdk) { Fail "нет local.properties со sdk.dir" }
    $bt = Get-ChildItem "$sdk\build-tools" -Directory | Sort-Object Name -Descending |
        Where-Object { Test-Path (Join-Path $_.FullName "apksigner.bat") } | Select-Object -First 1
    if (-not $bt) { Fail "apksigner.bat не найден в build-tools" }
    $out = "$root\app\build\outputs\apk\release\app-release.apk"
    Write-Host "Подпись через apksigner..." -ForegroundColor Cyan
    & cmd /c "`"$($bt.FullName)\apksigner.bat`" sign --ks `"$root\$($props['storeFile'])`" --ks-pass pass:$($props['storePassword']) --key-pass pass:$($props['keyPassword']) --out `"$out`" `"$apkUnsigned`""
    if ($LASTEXITCODE -ne 0) { Fail "apksigner упал" }
    $apk = $out
} else {
    Fail "APK не найден в app\build\outputs\apk\release\"
}

# --- 4. Репозиторий и токен ---
$checker = Get-Content "$root\app\src\main\java\com\nex\vpn\UpdateChecker.kt" -Raw
if ($checker -notmatch 'GITHUB_REPO\s*=\s*"([^"]+)"') { Fail "не нашёл GITHUB_REPO в UpdateChecker.kt" }
$repo = $Matches[1]
if ($repo -eq "OWNER/REPO") { Fail "впиши свой репозиторий в UpdateChecker.kt (GITHUB_REPO = `"owner/repo`")" }
$token = $props["githubToken"]
if (-not $token -or $token -like "*ВСТАВЬ*") {
    Write-Host "Токен не задан — публикация пропущена." -ForegroundColor Yellow
    Write-Host "APK готов локально: $apk"
    Write-Host "Вставь githubToken в keystore.properties и запусти ещё раз."
    exit 0
}

# git: код должен быть закоммичен и запушен (тег создаётся из дефолтной ветки)
$dirty = git -C $root status --porcelain 2>$null
if ($dirty) { Write-Host "ВНИМАНИЕ: есть незакоммиченные изменения — релиз уедет без них!" -ForegroundColor Yellow }
$pushed = git -C $root log origin/HEAD..HEAD --oneline 2>$null
if ($pushed) { Write-Host "ВНИМАНИЕ: коммиты не запушены — обновление увидят не все!" -ForegroundColor Yellow }

# --- 5. Публикация GitHub Release ---
$h = @{
    Authorization           = "Bearer $token"
    Accept                  = "application/vnd.github+json"
    "X-GitHub-Api-Version"  = "2022-11-28"
}
Write-Host "Публикация релиза v$version в $repo ..." -ForegroundColor Cyan
$body = @{ tag_name = "v$version"; name = "NexVPN v$version"; body = "NexVPN v$version`n`nBased on NexVPN by VikNex" } | ConvertTo-Json
try {
    $rel = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$repo/releases" -Headers $h -Body $body -ContentType "application/json"
} catch {
    $msg = $_.ErrorDetails.Message
    if ($msg -like "*already_exists*") { Fail "тег v$version уже существует — подними versionName/versionCode в app/build.gradle" }
    Fail "создание релиза не удалось: $msg"
}
$assetUri = "https://uploads.github.com/repos/$repo/releases/$($rel.id)/assets?name=NexVPN-v$version.apk"
Invoke-RestMethod -Method Post -Uri $assetUri -Headers $h -ContentType "application/vnd.android.package-archive" -InFile $apk | Out-Null

Write-Host "ГОТОВО!" -ForegroundColor Green
Write-Host "Релиз: $($rel.html_url)"
Write-Host "Телефоны предложат обновление в течение ~10 минут (если versionName в приложении старая)."
