package com.nex.vpn

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.switchmaterial.SwitchMaterial
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.File
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    // findViewById вместо viewBinding: генерённые классы не подхватывались котлином
    private lateinit var tvStatus: TextView
    private lateinit var tvCountry: TextView
    private lateinit var tvProtocol: TextView
    private lateinit var tvPing: TextView
    private lateinit var tvKeysActive: TextView
    private lateinit var tvBypassHint: TextView
    private lateinit var btnPlay: FloatingActionButton
    private lateinit var switchBypass: SwitchMaterial
    private lateinit var switchBestPing: SwitchMaterial

    private var pingJob: Job? = null
    private val protocolIdle = "Протокол: —"

    // --- автообновление с GitHub ---
    private val updateHandler = Handler(Looper.getMainLooper())
    private var updateDialogShowing = false
    private var pendingApk: File? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, i: Intent?) {
            val state = i?.getStringExtra("state") ?: return
            val detail = i.getStringExtra("detail") ?: ""
            when (state) {
                "connected" -> {
                    tvStatus.text = getString(R.string.status_connected)
                    tvStatus.setTextColor(getColor(R.color.nex_green))
                    btnPlay.setImageResource(android.R.drawable.ic_media_pause)
                    val k = NexVpnService.currentKey
                    if (k != null) {
                        tvCountry.text = k.countryEmoji
                        tvProtocol.text = "${getString(R.string.label_protocol)}: ${k.protocolLabel}"
                        tvProtocol.setTextColor(getColor(R.color.nex_white))
                        startPingLoop()
                    }
                }
                "starting" -> {
                    tvStatus.text = getString(R.string.status_connecting)
                    tvStatus.setTextColor(getColor(R.color.nex_purple))
                }
                "stopped", "disconnected" -> {
                    tvStatus.text = getString(R.string.status_idle)
                    tvStatus.setTextColor(getColor(R.color.nex_gray))
                    btnPlay.setImageResource(android.R.drawable.ic_media_play)
                    tvCountry.text = "—"
                    resetProtocolPing()
                }
                "error" -> {
                    tvStatus.text = detail.ifEmpty { "ошибка" }
                    tvStatus.setTextColor(getColor(R.color.nex_red))
                    btnPlay.setImageResource(android.R.drawable.ic_media_play)
                    tvCountry.text = "—"
                    resetProtocolPing()
                }
            }
        }
    }

    private fun resetProtocolPing() {
        pingJob?.cancel()
        pingJob = null
        tvProtocol.text = protocolIdle
        tvProtocol.setTextColor(getColor(R.color.nex_gray))
        tvPing.text = "—"
        tvPing.setTextColor(getColor(R.color.nex_white))
    }

    /** Периодический TCP-пинг ключа, пока подключены */
    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = scope.launch {
            while (isActive) {
                val k = NexVpnService.currentKey
                if (k == null) break
                val ms = withContext(Dispatchers.IO) { KeyRepository.tcpPing(k.host, k.port) }
                if (!NexVpnService.isRunning) break
                if (ms >= 0) {
                    tvPing.text = "$ms мс"
                    tvPing.setTextColor(getColor(R.color.nex_green))
                } else {
                    tvPing.text = "—"
                    tvPing.setTextColor(getColor(R.color.nex_red))
                }
                delay(5000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvCountry = findViewById(R.id.tvCountry)
        tvProtocol = findViewById(R.id.tvProtocol)
        tvPing = findViewById(R.id.tvPing)
        tvKeysActive = findViewById(R.id.tvKeysActive)
        tvBypassHint = findViewById(R.id.tvBypassHint)
        btnPlay = findViewById(R.id.btnPlay)
        switchBypass = findViewById(R.id.switchBypass)
        switchBestPing = findViewById(R.id.switchBestPing)

        registerReceiver(stateReceiver, IntentFilter("com.nex.vpn.STATE"), RECEIVER_NOT_EXPORTED)

        btnPlay.setOnClickListener { toggleVpn() }
        switchBypass.setOnCheckedChangeListener { _, checked ->
            KeyRepository.bypassMode = checked
            tvBypassHint.text = if (checked)
                "Обход: reality-ключи для регионов с белыми списками"
            else
                "Обычные ключи: для работы без блокировок"
            refreshKeys()
        }
        switchBestPing.setOnCheckedChangeListener { _, checked ->
            KeyRepository.bestByPing = checked
        }

        refreshKeys()
        scheduleUpdateChecks()
    }

    // region --- Автообновление (GitHub Releases) ---

    private fun scheduleUpdateChecks() {
        val tick = object : Runnable {
            override fun run() {
                checkForUpdate()
                updateHandler.postDelayed(this, UpdateChecker.CHECK_INTERVAL_MS)
            }
        }
        updateHandler.postDelayed(tick, 5_000) // первый чек через 5 с после запуска
    }

    private fun checkForUpdate() {
        scope.launch {
            val upd = try { UpdateChecker.check() } catch (_: Exception) { null } ?: return@launch
            if (updateDialogShowing) return@launch
            updateDialogShowing = true
            val notes = upd.notes?.trim().orEmpty().takeIf { it.isNotEmpty() }?.take(600)
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle("Доступна новая версия ${upd.version}")
                .setMessage(notes ?: "Обновить приложение сейчас?")
                .setPositiveButton("Обновить") { _, _ -> downloadUpdate(upd) }
                .setNegativeButton("Позже", null)
                .setOnDismissListener { updateDialogShowing = false }
                .show()
        }
    }

    private fun downloadUpdate(upd: UpdateChecker.Update) {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                try { UpdateChecker.downloadApk(this@MainActivity, upd) } catch (_: Exception) { null }
            }
            if (file == null) {
                Toast.makeText(this@MainActivity, "Не удалось скачать обновление", Toast.LENGTH_LONG).show()
                return@launch
            }
            installApk(file)
        }
    }

    private fun installApk(file: File) {
        pendingApk = file
        if (!UpdateChecker.canInstallUnknownApps(this)) {
            Toast.makeText(this, "Разрешите установку из неизвестных источников", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        tryInstall()
    }

    private fun tryInstall() {
        val file = pendingApk ?: return
        if (!UpdateChecker.canInstallUnknownApps(this)) return
        pendingApk = null
        try {
            startActivity(UpdateChecker.installIntent(this, file))
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка установки: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        // вернулись из настроек «неизвестных источников» — доводим установку
        tryInstall()
    }

    // endregion

    private fun toggleVpn() {
        if (NexVpnService.isRunning) stopVpn() else startVpn()
    }

    private fun startVpn() {
        // VPN permission
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            startActivityForResult(intent, REQ_VPN)
            return
        }
        launchService()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == REQ_VPN && res == Activity.RESULT_OK) launchService()
    }

    private var watchdog: android.os.Handler? = null
    private val watchdogTask = Runnable {
        if (!NexVpnService.isRunning) {
            tvStatus.text = "не удалось подключиться — проверь ключи/сеть"
            tvStatus.setTextColor(getColor(R.color.nex_red))
            btnPlay.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    private fun launchService() {
        val i = Intent(this, NexVpnService::class.java)
            .setAction(NexVpnService.ACTION_START)
            .putExtra("bypass", switchBypass.isChecked)
        startForegroundService(i)
        tvStatus.text = getString(R.string.status_connecting)
        tvStatus.setTextColor(getColor(R.color.nex_purple))
        // страховка: если сервис не отзвонился за 40 с, не висим в «подключение» вечно
        val h = watchdog ?: android.os.Handler(android.os.Looper.getMainLooper()).also { watchdog = it }
        h.removeCallbacks(watchdogTask)
        h.postDelayed(watchdogTask, 40_000)
    }

    private fun stopVpn() {
        val i = Intent(this, NexVpnService::class.java)
            .setAction(NexVpnService.ACTION_STOP)
        startService(i)
        tvStatus.text = getString(R.string.status_idle)
        tvStatus.setTextColor(getColor(R.color.nex_gray))
        btnPlay.setImageResource(android.R.drawable.ic_media_play)
        tvCountry.text = "—"
        resetProtocolPing()
    }

    private fun refreshKeys() {
        scope.launch {
            val count = KeyRepository.countActive(switchBypass.isChecked)
            tvKeysActive.text = count.toString()
        }
    }

    override fun onDestroy() {
        unregisterReceiver(stateReceiver)
        updateHandler.removeCallbacksAndMessages(null)
        scope.cancel()
        super.onDestroy()
    }

    companion object { const val REQ_VPN = 42 }
}
