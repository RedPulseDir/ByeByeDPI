package org.romanvht.byedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.databinding.ActivityMainBinding
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.system.exitProcess

class MainActivity : BaseActivity() {
    private lateinit var binding: ActivityMainBinding

    companion object {
        private val TAG: String = MainActivity::class.java.simpleName
        private const val BATTERY_OPTIMIZATION_REQUESTED = "battery_optimization_requested"

        private fun collectLogs(): String? =
            try {
                Runtime.getRuntime()
                    .exec("logcat *:D -d")
                    .inputStream.bufferedReader()
                    .use { it.readText() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to collect logs", e)
                null
            }
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateStatus()
            }
        }

    private val logsRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { log ->
            lifecycleScope.launch(Dispatchers.IO) {
                val logs = collectLogs()

                if (logs == null) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.logs_failed,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    val uri = log.data?.data ?: run {
                        Log.e(TAG, "No data in result")
                        return@launch
                    }
                    contentResolver.openOutputStream(uri)?.use {
                        try {
                            it.write(logs.toByteArray())
                        } catch (e: IOException) {
                            Log.e(TAG, "Failed to save logs", e)
                        }
                    } ?: run {
                        Log.e(TAG, "Failed to open output stream")
                    }
                }
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Received intent: ${intent?.action}")

            if (intent == null) {
                Log.w(TAG, "Received null intent")
                return
            }

            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            if (sender == null) {
                Log.w(TAG, "Received intent with unknown sender: $senderOrd")
                return
            }

            when (val action = intent.action) {
                STARTED_BROADCAST,
                STOPPED_BROADCAST -> updateStatus()

                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        getString(R.string.failed_to_start, sender.name),
                        Toast.LENGTH_SHORT,
                    ).show()
                    updateStatus()
                }

                else -> Log.w(TAG, "Unknown action: $action")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        binding.statusButtonCard.setOnClickListener {
            binding.statusButtonCard.isClickable = false

            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> start()
                AppStatus.Running -> stop()
            }

            binding.statusButtonCard.postDelayed({
                binding.statusButtonCard.isClickable = true
            }, 1000)
        }

        binding.settingsButton.setOnClickListener {
            val (status, _) = appStatus

            if (status == AppStatus.Halted) {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            } else {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
            }
        }

        binding.editorButton.setOnClickListener {
            val useCmdSettings = getPreferences().getBoolean("byedpi_enable_cmd_settings", false)

            if (!useCmdSettings && appStatus.first == AppStatus.Running) {
                Toast.makeText(this, R.string.settings_unavailable, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val intent = Intent(this, SettingsActivity::class.java)
            intent.putExtra("open_fragment", if (useCmdSettings) "cmd" else "ui")
            startActivity(intent)
        }

        binding.testProxyButton.setOnClickListener {
            startActivity(Intent(this, TestActivity::class.java))
        }

        binding.domainListsButton.setOnClickListener {
            val intent = Intent(this, TestSettingsActivity::class.java)
            intent.putExtra("open_fragment", "domain_lists")
            startActivity(intent)
        }

        // СЕКРЕТНАЯ КНОПКА - долгое нажатие на версию
        binding.tvVersionSecret.apply {
            text = "v${BuildConfig.VERSION_NAME}"
            setOnLongClickListener {
                showSecretFeaturesDialog()
                true
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        } else {
            requestBatteryOptimization()
        }

        if (getPreferences().getBoolean("auto_connect", false) && appStatus.first != AppStatus.Running) {
            this.start()
        }

        ShortcutUtils.update(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        updateButtonsVisibility()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1) {
            requestBatteryOptimization()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        // Секретный пункт меню
        menu?.add(0, R.id.action_secret_features, 0, "🔐 Секретные функции")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val (status, _) = appStatus

        return when (item.itemId) {
            R.id.action_save_logs -> {
                val intent =
                    Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TITLE, "byedpi.log")
                    }

                logsRegister.launch(intent)
                true
            }

            R.id.action_close_app -> {
                if (status == AppStatus.Running) stop()
                finishAffinity()
                android.os.Process.killProcess(android.os.Process.myPid())
                exitProcess(0)
            }

            R.id.action_secret_features -> {
                showSecretFeaturesDialog()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun start() {
        when (getPreferences().mode()) {
            Mode.VPN -> {
                val intentPrepare = VpnService.prepare(this)
                if (intentPrepare != null) {
                    vpnRegister.launch(intentPrepare)
                } else {
                    ServiceManager.start(this, Mode.VPN)
                }
            }

            Mode.Proxy -> ServiceManager.start(this, Mode.Proxy)
        }
    }

    private fun stop() {
        ServiceManager.stop(this)
    }

    private fun updateStatus() {
        val (status, mode) = appStatus

        Log.i(TAG, "Updating status: $status, $mode")

        val preferences = getPreferences()
        val (ip, port) = preferences.getProxyIpAndPort()

        binding.proxyAddress.text = getString(R.string.proxy_address, ip, port)

        when (status) {
            AppStatus.Halted -> {
                val typedValue = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.colorPrimary, typedValue,true)
                binding.statusButtonCard.setCardBackgroundColor(typedValue.data)
                binding.statusButtonIcon.clearColorFilter()

                when (preferences.mode()) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_disconnected)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_down)
                    }
                }
            }

            AppStatus.Running -> {
                binding.statusButtonCard.setCardBackgroundColor(ContextCompat.getColor(this, R.color.green_active))
                binding.statusButtonIcon.setColorFilter(ContextCompat.getColor(this, android.R.color.white))

                when (mode) {
                    Mode.VPN -> {
                        binding.statusText.setText(R.string.vpn_connected)
                    }

                    Mode.Proxy -> {
                        binding.statusText.setText(R.string.proxy_up)
                    }
                }
            }
        }
    }

    private fun updateButtonsVisibility() {
        val useCmdSettings = getPreferences().getBoolean("byedpi_enable_cmd_settings", false)
        val visibility = if (useCmdSettings) View.VISIBLE else View.GONE
        binding.cmdButtonsRow.visibility = visibility
    }

    private fun requestBatteryOptimization() {
        val preferences = getPreferences()
        val alreadyRequested = preferences.getBoolean(BATTERY_OPTIMIZATION_REQUESTED, false)

        if (!alreadyRequested && !BatteryUtils.isOptimizationDisabled(this)) {
            BatteryUtils.requestBatteryOptimization(this)
            preferences.edit { putBoolean(BATTERY_OPTIMIZATION_REQUESTED, true) }
        }
    }

    // ============== СЕКРЕТНЫЕ ФУНКЦИИ ==============
    
    private fun showSecretFeaturesDialog() {
        val options = arrayOf(
            "💀 Принудительно закрыть прокси (jniForceClose)",
            "🚀 Отправить Intent в ToggleActivity",
            "📱 Полный дамп (logcat + dmesg)",
            "📤 Экспорт ВСЕХ настроек",
            "📥 Импорт ВСЕХ настроек",
            "🐛 Включить DEBUG режим (--debug)",
            "🧪 Запустить подбор стратегий",
            "📋 Подстановка {list:имя}",
            "⚡ Быстрое применение стратегии",
            "📋 Текущая стратегия в буфер"
        )

        AlertDialog.Builder(this)
            .setTitle("🔐 Секретные функции")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> forceCloseProxy()
                    1 -> showIntentDialog()
                    2 -> dumpFullLogs()
                    3 -> exportEverything()
                    4 -> importEverything()
                    5 -> toggleDebugMode()
                    6 -> startActivity(Intent(this, TestActivity::class.java))
                    7 -> showListSubstitutionDialog()
                    8 -> showQuickApplyDialog()
                    9 -> copyCurrentStrategy()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun forceCloseProxy() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val proxy = ByeDpiProxy()
                val result = proxy.jniForceClose()
                
                withContext(Dispatchers.Main) {
                    if (result == 0) {
                        Toast.makeText(this@MainActivity, "✅ Прокси принудительно закрыт", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "❌ Ошибка закрытия", Toast.LENGTH_SHORT).show()
                    }
                    updateStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showIntentDialog() {
        val input = EditText(this).apply {
            hint = "Введите стратегию"
            setText(getPreferences().getString("byedpi_cmd_args", ""))
            minLines = 3
        }

        AlertDialog.Builder(this)
            .setTitle("Intent в ToggleActivity")
            .setView(input)
            .setPositiveButton("Отправить") { _, _ ->
                val command = input.text.toString().trim()
                val intent = Intent(this, ToggleActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("strategy", command)
                }
                startActivity(intent)
                Toast.makeText(this, "Intent отправлен", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun dumpFullLogs() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val logcat = Runtime.getRuntime().exec("logcat -d").inputStream.bufferedReader().readText()
                val dmesg = try {
                    Runtime.getRuntime().exec("dmesg").inputStream.bufferedReader().readText()
                } catch (e: Exception) {
                    "dmesg недоступен"
                }

                val fullLog = buildString {
                    appendLine("=== LOGCAT ===")
                    appendLine(logcat)
                    appendLine("\n=== DMESG ===")
                    appendLine(dmesg)
                }

                val file = java.io.File(cacheDir, "full_dump_${System.currentTimeMillis()}.txt")
                file.writeText(fullLog)

                withContext(Dispatchers.Main) {
                    ClipboardUtils.copy(this@MainActivity, file.absolutePath, "path")
                    Toast.makeText(this@MainActivity, "✅ Сохранено: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun exportEverything() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "byedpi_full_export_${System.currentTimeMillis()}.json")
        }
        exportLauncher.launch(intent)
    }

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    SettingsUtils.exportSettings(this@MainActivity, uri)
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "✅ Экспорт выполнен", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun importEverything() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        importLauncher.launch(intent)
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        result.data?.data?.let { uri ->
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    SettingsUtils.importSettings(this@MainActivity, uri) {
                        lifecycleScope.launch(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "✅ Импорт выполнен", Toast.LENGTH_SHORT).show()
                            updateStatus()
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "❌ Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun toggleDebugMode() {
        val prefs = getPreferences()
        val current = prefs.getBoolean("debug_mode", false)
        prefs.edit { putBoolean("debug_mode", !current) }
        
        Toast.makeText(this, 
            "Debug режим: ${if (!current) "ВКЛЮЧЕН" else "ВЫКЛЮЧЕН"}", 
            Toast.LENGTH_SHORT).show()
        
        if (!current) {
            val cmdArgs = prefs.getString("byedpi_cmd_args", "") ?: ""
            if (!cmdArgs.contains("--debug")) {
                prefs.edit { putString("byedpi_cmd_args", "$cmdArgs --debug") }
            }
        }
    }

    private fun showListSubstitutionDialog() {
        val lists = DomainListUtils.getLists(this)
        val listNames = lists.map { it.name }.toTypedArray()
        
        if (listNames.isEmpty()) {
            Toast.makeText(this, "Нет сохраненных списков", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Выберите список для подстановки")
            .setItems(listNames) { _, which ->
                val list = lists[which]
                val placeholder = "{list:${list.name}}"
                
                val prefs = getPreferences()
                val currentCmd = prefs.getString("byedpi_cmd_args", "") ?: ""
                prefs.edit { putString("byedpi_cmd_args", "$currentCmd $placeholder") }
                
                ClipboardUtils.copy(this, placeholder, "placeholder")
                Toast.makeText(this, "✅ Добавлено: $placeholder", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun showQuickApplyDialog() {
        val input = EditText(this).apply {
            hint = "Введите стратегию"
            setText(getPreferences().getString("byedpi_cmd_args", ""))
            minLines = 3
        }

        AlertDialog.Builder(this)
            .setTitle("Быстрое применение")
            .setView(input)
            .setPositiveButton("Применить") { _, _ ->
                val command = input.text.toString().trim()
                val prefs = getPreferences()
                prefs.edit { putString("byedpi_cmd_args", command) }
                
                if (appStatus.first == AppStatus.Running) {
                    ServiceManager.restart(this, prefs.mode())
                    Toast.makeText(this, "✅ Сервис перезапущен", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "✅ Стратегия сохранена", Toast.LENGTH_SHORT).show()
                }
                updateStatus()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun copyCurrentStrategy() {
        val current = getPreferences().getString("byedpi_cmd_args", "") ?: ""
        ClipboardUtils.copy(this, current, "strategy")
        Toast.makeText(this, "✅ Стратегия скопирована", Toast.LENGTH_SHORT).show()
    }
}
