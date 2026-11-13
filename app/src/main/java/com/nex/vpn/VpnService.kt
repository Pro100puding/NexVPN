package com.nex.vpn

import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import java.io.File

/**
 * NexVPN VpnService.
 *
 * Архитектура: Android TUN -> fd передаётся в xray-core (libv2ray.aar) через startLoop(config, fd).
 * Xray сам читает TUN (tun2socks-подобный режим через dokodemo), роутит по правилам:
 *  - порт 80 -> blacklist (жёсткий рез HTTP)
 *  - geosite:category-ads -> блок
 *  - всё остальное -> freedom outbound через vless/reality сервер ключа
 * DNS: DoH Cloudflare/Google -- а не через провайдера, чтобы не подменили.
 */
class NexVpnService : VpnService(), CoreCallbackHandler {

    companion object {
        const val ACTION_START = "com.nex.vpn.START"
        const val ACTION_STOP = "com.nex.vpn.STOP"
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "nexvpn_main"

        var isRunning = false
        var currentStatus = ""
        var currentKey: VlessKey? = null
        var appCtx: android.content.Context? = null

        private fun broadcast(state: String) {
            android.util.Log.i("NexVPN", "broadcast state=$state detail=$currentStatus")
            val i = Intent("com.nex.vpn.STATE").setPackage("com.nex.vpn")
            i.putExtra("state", state)
            i.putExtra("detail", currentStatus)
            appCtx?.sendBroadcast(i)
        }
    }

    private var controller: CoreController? = null
    private var tun: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        appCtx = applicationContext
    }

    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // stopLoop может ждать Go-мьютекс (и делать callback в этот же поток) —
                // на главном потоке это гарантированный ANR/дедлок
                Thread({ stopVpn() }, "nexvpn-stop").start()
                return START_NOT_STICKY
            }
            else -> {
                // ВАЖНО: никакой сети в главном потоке (иначе ANR и «вечное подключение»).
                // Уведомление в foreground надо поднять сразу — иначе API 34+ убьёт сервис.
                try { startForeground(NOTIFICATION_ID, buildNotification()) } catch (_: Exception) {}
                // сообщаем UI, что реально начали работу (а не просто повесили текст)
                currentStatus = "подбираю ключ…"
                broadcast("starting")

                worker?.interrupt()
                worker = Thread({
                    try {
                        startVpn(intent)
                    } catch (e: Throwable) {
                        android.util.Log.e("NexVPN", "startVpn failed", e)
                        currentStatus = "ошибка: ${e.javaClass.simpleName}: ${e.message}"
                        broadcast("error")
                        stopSelf()
                    }
                }, "nexvpn-start").also { it.start() }
            }
        }
        return START_STICKY
    }

    private fun startVpn(intent: Intent?) {
        val bypass = intent?.getBooleanExtra("bypass", false) ?: false
        // Ключи тянем ПЕРЕД выбором: на старте кэш пустой, иначе «нет живых ключей»
        // вызывается из фонового Thread → runBlocking допустим (main-поток не блокируем)
        val key = kotlinx.coroutines.runBlocking { KeyRepository.pickKeyLive(bypass) }
        if (key == null) {
            currentStatus = "нет живых ключей"
            broadcast("error")
            stopSelf()
            return
        }
        currentKey = key
        currentStatus = key.name?.takeIf { it.isNotEmpty() } ?: key.host
        broadcast("starting")

        // Foreground-уведомление уже поднято в onStartCommand

        // ---- поднимаем TUN ----
        tun = Builder()
            .setSession("NexVPN")
            .setMtu(1500)
            .addAddress("26.26.26.1", 30)
            .addDnsServer("26.26.26.2") // фейковый DNS -> xray перехватит и пойдёт в DoH
            .addRoute("0.0.0.0", 0)
            .apply {
                // свой трафик не пускаем в собственный туннель:
                // иначе исходящее соединение xray к VLESS-серверу вернётся в tun0 (петля)
                try { addDisallowedApplication(packageName) } catch (_: Exception) {}
            }
            .setBlocking(true)
            .establish() ?: run {
            currentStatus = "не удалось поднять TUN"
            broadcast("error")
            stopSelf()
            return
        }

        // ---- инициализация окружения xray ----
        val envDir = File(filesDir, "xray").apply { mkdirs() }
        copyAssetIfMissing("geosite.dat", envDir)
        copyAssetIfMissing("geoip.dat", envDir)
        Libv2ray.initCoreEnv(envDir.absolutePath, "")

        // ---- контроллер и конфиг ----
        controller = Libv2ray.newCoreController(this)
        val config = XrayConfigBuilder.build(key, bypass, allowLan = false)
        android.util.Log.i("NexVPN", "xray config: $config")
        try {
            controller!!.startLoop(config, tun!!.fd)
        } catch (e: Exception) {
            android.util.Log.e("NexVPN", "startLoop failed", e)
            currentStatus = "xray не стартовал: ${e.message}"
            broadcast("error")
            stopVpn()
            return
        }
        isRunning = true
        // Go перезаписал currentStatus своим «Started successfully...» —
        // для UI возвращаем имя ключа (как задумано)
        currentStatus = key.name?.takeIf { it.isNotEmpty() } ?: key.host
        broadcast("connected")
    }

    private fun copyAssetIfMissing(name: String, dir: File) {
        val out = File(dir, name)
        if (out.exists() && out.length() > 0) return
        try {
            assets.open(name).use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: Exception) {
            android.util.Log.e("NexVPN", "не удалось скопировать $name", e)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        val ch = android.app.NotificationChannel(CHANNEL_ID, "NexVPN", android.app.NotificationManager.IMPORTANCE_LOW)
        nm.createNotificationChannel(ch)
        val pi = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE
        )
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("NexVPN активен")
            .setContentText(currentStatus)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun stopVpn() {
        if (!stopping.compareAndSet(false, true)) return
        Thread({
            isRunning = false
            // Порядок: сначала гасим xray (он держит дублированный fd), потом наш fd.
            // Всё в фоне: stopLoop блокируется на Go coreMutex.
            try { controller?.stopLoop() } catch (_: Exception) {}
            controller = null
            try { tun?.close() } catch (_: Exception) {}
            tun = null
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
            stopSelf()
            broadcast("disconnected")
            stopping.set(false)
        }, "nexvpn-stop").start()
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }

    // ---- CoreCallbackHandler: колбэки из Go-ядра ----
    override fun onEmitStatus(p0: Long, p1: String?): Long {
        android.util.Log.i("NexVPN", "GoStatus[$p0]: $p1")
        currentStatus = p1 ?: ""
        broadcast("status")
        return 0L
    }

    // Go StopLoop держит coreMutex и вызывает этот callback с того же потока.
    // Любой вызов stopLoop/stopVpn отсюда = повторный захват мьютекса = дедлок (ANR).
    override fun shutdown(): Long { return 0L }
    override fun startup(): Long { return 0L }

    override fun onRevoke() {
        // система забрала VPN-разрешение или подняла свой VPN
        stopVpn()
    }
}
