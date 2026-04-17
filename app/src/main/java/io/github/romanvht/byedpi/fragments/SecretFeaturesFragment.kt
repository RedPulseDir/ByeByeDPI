// app/src/main/java/io/github/romanvht/byedpi/fragments/SecretFeaturesFragment.kt

package io.github.romanvht.byedpi.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.activities.ToggleActivity
import io.github.romanvht.byedpi.core.ByeDpiProxy
import io.github.romanvht.byedpi.data.AppStatus
import io.github.romanvht.byedpi.data.Mode
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class SecretFeaturesFragment : Fragment() {

    private lateinit var btnForceClose: Button
    private lateinit var btnSendIntent: Button
    private lateinit var btnDumpLogs: Button
    private lateinit var btnExportFull: Button
    private lateinit var btnImportFull: Button
    private lateinit var btnDebugMode: Button
    private lateinit var btnTestAllStrategies: Button
    private lateinit var btnListSubstitution: Button
    private lateinit var btnQuickToggle: Button
    private lateinit var etCustomCommand: EditText
    private lateinit var tvStatus: TextView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_secret_features, container, false)
        
        initViews(view)
        setupListeners()
        updateStatus()
        
        return view
    }

    private fun initViews(view: View) {
        btnForceClose = view.findViewById(R.id.btn_force_close)
        btnSendIntent = view.findViewById(R.id.btn_send_intent)
        btnDumpLogs = view.findViewById(R.id.btn_dump_logs)
        btnExportFull = view.findViewById(R.id.btn_export_full)
        btnImportFull = view.findViewById(R.id.btn_import_full)
        btnDebugMode = view.findViewById(R.id.btn_debug_mode)
        btnTestAllStrategies = view.findViewById(R.id.btn_test_all_strategies)
        btnListSubstitution = view.findViewById(R.id.btn_list_substitution)
        btnQuickToggle = view.findViewById(R.id.btn_quick_toggle)
        etCustomCommand = view.findViewById(R.id.et_custom_command)
        tvStatus = view.findViewById(R.id.tv_status)
    }

    private fun setupListeners() {
        btnForceClose.setOnClickListener {
            forceCloseProxy()
        }

        btnSendIntent.setOnClickListener {
            showIntentDialog()
        }

        btnDumpLogs.setOnClickListener {
            dumpFullLogs()
        }

        btnExportFull.setOnClickListener {
            exportEverything()
        }

        btnImportFull.setOnClickListener {
            importEverything()
        }

        btnDebugMode.setOnClickListener {
            toggleDebugMode()
        }

        btnTestAllStrategies.setOnClickListener {
            testAllStrategies()
        }

        btnListSubstitution.setOnClickListener {
            showListSubstitutionDialog()
        }

        btnQuickToggle.setOnClickListener {
            quickToggleWithCurrentCommand()
        }
    }

    private fun updateStatus() {
        val (status, mode) = appStatus
        tvStatus.text = "Статус: $status, Режим: $mode"
    }

    // 1. Принудительное закрытие прокси
    private fun forceCloseProxy() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val proxy = ByeDpiProxy()
                val result = proxy.jniForceClose()
                
                withContext(Dispatchers.Main) {
                    if (result == 0) {
                        Toast.makeText(requireContext(), "Прокси принудительно закрыт", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "Ошибка закрытия (fd: $result)", Toast.LENGTH_SHORT).show()
                    }
                    updateStatus()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 2. Отправка Intent в ToggleActivity
    private fun showIntentDialog() {
        val options = arrayOf(
            "Только обновить стратегию",
            "Только старт",
            "Только стоп",
            "Обновить и переключить",
            "Старт с VPN (если не запущен)",
            "Стоп (если запущен)"
        )

        AlertDialog.Builder(requireContext())
            .setTitle("Управление через Intent")
            .setItems(options) { _, which ->
                val command = etCustomCommand.text.toString().trim()
                sendToggleIntent(which, command)
            }
            .show()
    }

    private fun sendToggleIntent(action: Int, command: String) {
        val intent = Intent(requireContext(), ToggleActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            
            when (action) {
                0 -> {
                    putExtra("strategy", command)
                    putExtra("only_update", true)
                }
                1 -> putExtra("only_start", true)
                2 -> putExtra("only_stop", true)
                3 -> putExtra("strategy", command)
                4 -> {
                    if (appStatus.first == AppStatus.Halted) {
                        putExtra("only_start", true)
                    }
                }
                5 -> {
                    if (appStatus.first == AppStatus.Running) {
                        putExtra("only_stop", true)
                    }
                }
            }
        }

        startActivity(intent)
        Toast.makeText(requireContext(), "Intent отправлен", Toast.LENGTH_SHORT).show()
    }

    // 3. Полный дамп логов
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
                    appendLine("\n=== STATUS ===")
                    appendLine("App Status: ${appStatus.first}")
                    appendLine("Mode: ${appStatus.second}")
                    appendLine("Prefs: ${getPreferences().all}")
                }

                val file = File(requireContext().cacheDir, "full_dump_${System.currentTimeMillis()}.txt")
                file.writeText(fullLog)

                withContext(Dispatchers.Main) {
                    ClipboardUtils.copy(requireContext(), file.absolutePath, "path")
                    Toast.makeText(requireContext(), "Дамп сохранен: ${file.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // 4. Экспорт всего (включая доменные списки)
    private fun exportEverything() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "byedpi_full_export_${System.currentTimeMillis()}.json")
        }
        
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        SettingsUtils.exportSettings(requireContext(), uri)
                        
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Экспорт выполнен", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Ошибка экспорта: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }.launch(intent)
    }

    // 5. Импорт всего
    private fun importEverything() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
        }
        
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
            result.data?.data?.let { uri ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        SettingsUtils.importSettings(requireContext(), uri) {
                            lifecycleScope.launch(Dispatchers.Main) {
                                Toast.makeText(requireContext(), "Импорт выполнен", Toast.LENGTH_SHORT).show()
                                updateStatus()
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(requireContext(), "Ошибка импорта: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }.launch(intent)
    }

    // 6. Включение debug режима
    private fun toggleDebugMode() {
        val prefs = getPreferences()
        val current = prefs.getBoolean("debug_mode", false)
        prefs.edit { putBoolean("debug_mode", !current) }
        
        Toast.makeText(requireContext(), 
            "Debug режим: ${if (!current) "ВКЛЮЧЕН" else "ВЫКЛЮЧЕН"}", 
            Toast.LENGTH_SHORT).show()
        
        // Добавляем --debug в аргументы если включен
        if (!current) {
            val cmdArgs = prefs.getString("byedpi_cmd_args", "") ?: ""
            if (!cmdArgs.contains("--debug")) {
                prefs.edit { putString("byedpi_cmd_args", "$cmdArgs --debug") }
            }
        }
    }

    // 7. Тест всех стратегий
    private fun testAllStrategies() {
        val intent = Intent(requireContext(), io.github.romanvht.byedpi.activities.TestActivity::class.java)
        startActivity(intent)
    }

    // 8. Подстановка списков доменов
    private fun showListSubstitutionDialog() {
        val lists = DomainListUtils.getLists(requireContext())
        val listNames = lists.map { it.name }.toTypedArray()
        
        if (listNames.isEmpty()) {
            Toast.makeText(requireContext(), "Нет сохраненных списков", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Выберите список для подстановки")
            .setItems(listNames) { _, which ->
                val list = lists[which]
                val placeholder = "{list:${list.name}}"
                
                val currentCmd = etCustomCommand.text.toString()
                if (currentCmd.isNotBlank()) {
                    etCustomCommand.setText("$currentCmd $placeholder")
                } else {
                    etCustomCommand.setText(placeholder)
                }
                
                ClipboardUtils.copy(requireContext(), placeholder, "placeholder")
                Toast.makeText(requireContext(), "Плейсхолдер скопирован: $placeholder", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // 9. Быстрое переключение с текущей командой
    private fun quickToggleWithCurrentCommand() {
        val command = etCustomCommand.text.toString().trim()
        
        if (command.isBlank()) {
            Toast.makeText(requireContext(), "Введите команду", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getPreferences()
        val oldCommand = prefs.getString("byedpi_cmd_args", "")
        
        prefs.edit { putString("byedpi_cmd_args", command) }
        
        if (appStatus.first == AppStatus.Running) {
            val mode = prefs.mode()
            ServiceManager.restart(requireContext(), mode)
            Toast.makeText(requireContext(), "Сервис перезапущен с новой стратегией", Toast.LENGTH_SHORT).show()
        } else {
            val mode = prefs.mode()
            if (mode == Mode.VPN && VpnService.prepare(requireContext()) != null) {
                Toast.makeText(requireContext(), "Требуется разрешение VPN", Toast.LENGTH_SHORT).show()
                prefs.edit { putString("byedpi_cmd_args", oldCommand) }
                return
            }
            ServiceManager.start(requireContext(), mode)
            Toast.makeText(requireContext(), "Сервис запущен с новой стратегией", Toast.LENGTH_SHORT).show()
        }
        
        updateStatus()
    }
}
