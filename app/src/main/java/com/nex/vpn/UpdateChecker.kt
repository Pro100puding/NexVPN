package com.nex.vpn

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Автообновление через GitHub Releases.
 * Каждые CHECK_INTERVAL_MS приложение спрашивает api.github.com/repos/GITHUB_REPO/releases/latest;
 * если тег новее versionName и среди ассетов есть .apk — предлагаем обновиться.
 */
object UpdateChecker {

    /** ⚠️ ЗАМЕНИТЬ на свой публичный репозиторий в формате "owner/repo". Пока OWNER/REPO — проверка выключена. */
    const val GITHUB_REPO = "Pro100puding/NexVPN"

    const val CHECK_INTERVAL_MS = 10 * 60 * 1000L

    data class Update(val version: String, val apkUrl: String, val notes: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /** null = обновления нет (или проверка выключена/ошибка сети) */
    suspend fun check(): Update? = withContext(Dispatchers.IO) {
        if (GITHUB_REPO == "OWNER/REPO") return@withContext null
        try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            http.newCall(req).execute().use { resp ->
                android.util.Log.i("NexVPN", "update-check: HTTP ${resp.code}")
                if (!resp.isSuccessful) return@withContext null
                val json = JSONObject(resp.body?.string() ?: return@withContext null)
                val tag = json.optString("tag_name").trim().removePrefix("v")
                if (tag.isEmpty()) return@withContext null
                val assets = json.optJSONArray("assets") ?: return@withContext null
                var apkUrl: String? = null
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                        apkUrl = a.optString("browser_download_url")
                        break
                    }
                }
                val url = apkUrl?.takeIf { it.isNotEmpty() } ?: return@withContext null
                val newer = isNewer(tag, BuildConfig.VERSION_NAME)
                android.util.Log.i("NexVPN", "update-check: remote=$tag current=${BuildConfig.VERSION_NAME} newer=$newer")
                if (!newer) return@withContext null
                Update(tag, url, json.optString("body"))
            }
        } catch (e: Exception) {
            android.util.Log.w("NexVPN", "update-check failed: $e")
            null
        }
    }

    /** Сравнение версий без приставки v: "1.8.29" > "0.1.0" */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(s: String) = s.split('.', '-', '_').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Скачивает APK в cacheDir/updates/update.apk; null при ошибке */
    fun downloadApk(ctx: Context, upd: Update): File? {
        return try {
            val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "update.apk")
            if (out.exists()) out.delete()
            val req = Request.Builder().url(upd.apkUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                out.outputStream().use { fos ->
                    body.byteStream().use { it.copyTo(fos) }
                }
            }
            if (out.length() > 0L) out else null
        } catch (_: Exception) {
            null
        }
    }

    /** Может ли система вообще установить APK из неизвестных источников */
    fun canInstallUnknownApps(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** Интент установки скачанного APK через FileProvider */
    fun installIntent(ctx: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
