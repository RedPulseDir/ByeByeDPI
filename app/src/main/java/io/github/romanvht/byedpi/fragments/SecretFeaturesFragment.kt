package io.github.romanvht.byedpi.fragments

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
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
import io.github.romanvht.byedpi.utility.ClipboardUtils
import io.github.romanvht.byedpi.utility.HistoryUtils
import io.github.romanvht.byedpi.utility.getPreferences
import io.github.romanvht.byedpi.utility.getStringNotNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SecretFeaturesFragment : Fragment() {

    private lateinit var scrollView: ScrollView
    private lateinit var mainContainer: LinearLayout
    
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        scrollView = ScrollView(requireContext())
        mainContainer = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scrollView.addView(mainContainer)
        
        buildUI()
        
        return scrollView
    }
    
    private fun buildUI() {
        addHeader("Автоматизация через Intent")
        addDescription("Управление приложением из Tasker, MacroDroid, Termux")
        
        addCommandExample("Запустить со стратегией", 
            "am start -n io.github.romanvht.byedpi/.activities.ToggleActivity --es strategy \"-f-1 -Qr -s1+sm -a1\"")
        
        addCommandExample("Только сменить стратегию (без перезапуска)",
            "am start -n io.github.romanvht.byedpi/.activities.ToggleActivity --es strategy \"-Ku -a1\" --ez only_update true")
        
        addCommandExample("Только запустить",
            "am start -n io.github.romanvht.byedpi/.activities.ToggleActivity --ez only_start true")
        
        addCommandExample("Только остановить",
            "am start -n io.github.romanvht.byedpi/.activities.ToggleActivity --ez only_stop true")
        
        addDivider()
        
        addHeader("Динамические ярлыки (Shortcuts)")
        addDescription("Закрепите стратегию в истории команд (нажмите на булавку), и она появится в лаунчере при долгом нажатии на иконку. До 3 стратегий.")
        
        addDivider()
        
        addHeader("Совместная работа с AdGuard")
        addDescription("В режиме Proxy ByeByeDPI слушает 127.0.0.1:1080. Настройте AdGuard → Настройки → Прокси → SOCKS5 → 127.0.0.1:1080")
        
        addDivider()
        
        addHeader("Split tunneling (VPN)")
        addDescription("В настройках → Режим → VPN → Фильтр приложений. Blacklist = все через VPN, кроме выбранных. Whitelist = только выбранные через VPN.")
        
        addDivider()
        
        addHeader("Подстановка списков доменов в CMD")
        addDescription("В командной строке используйте {list:имя_списка}. Пример:")
        addCodeBlock("-f-1 -Qr -H:{list:youtube}")
        addDescription("Создать список можно в разделе Domain Lists")
        
        addDivider()
        
        addHeader("Скрытые параметры ByeDPI (v0.17.3)")
        addDescription("Работают через CMD-режим, но не вынесены в UI:")
        
        addParam("--tlsminor", "Модификация TLS minor version")
        addParam("--fake-tls-mod=origin", "Fake TLS пакет на основе реального")
        addParam("--fake-offset с флагами", "Расширенный контроль смещения")
        addParam("-Y", "Drop SACK (уже в UI, но мало кто знает)")
        addParam("-F", "TCP Fast Open (уже в UI)")
        
        addDivider()
        
        addHeader("Быстрое переключение стратегий")
        addDescription("Создайте ярлык на рабочем столе для любой стратегии через любой лаунчер, поддерживающий Activity:")
        
        val quickApplyLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        
        val strategyInput = EditText(requireContext()).apply {
            hint = "Введите стратегию"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val applyButton = Button(requireContext()).apply {
            text = "Применить и запустить"
            setOnClickListener {
                val strategy = strategyInput.text.toString()
                if (strategy.isNotBlank()) {
                    applyStrategy(strategy)
                } else {
                    Toast.makeText(requireContext(), "Введите стратегию", Toast.LENGTH_SHORT).show()
                }
            }
        }
        
        quickApplyLayout.addView(strategyInput)
        quickApplyLayout.addView(applyButton)
        mainContainer.addView(quickApplyLayout)
        
        addDivider()
        
        addHeader("Прямой вызов нативного ByeDPI")
        addDescription("Запуск без сервисов (для отладки)")
        
        val directRunLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16, 0, 16)
        }
        
        val directInput = EditText(requireContext()).apply {
            hint = "аргументы (без ciadpi)"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setText("-Ku -a1 -An -o1 -At,r,s -d1")
        }
        
        val directButton = Button(requireContext()).apply {
            text = "Запустить native"
            setOnClickListener {
                runNativeDirect(directInput.text.toString())
            }
        }
        
        directRunLayout.addView(directInput)
        directRunLayout.addView(directButton)
        mainContainer.addView(directRunLayout)
        
        addDivider()
        
        addHeader("Сброс всех настроек (включая списки доменов)")
        addDescription("Очищает всё: настройки, историю команд, списки доменов")
        
        val resetButton = Button(requireContext()).apply {
            text = "Полный сброс"
            setBackgroundColor(0xFFFF4444.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                resetEverything()
            }
        }
        mainContainer.addView(resetButton)
        
        addDivider()
        
        addHeader("Информация о версиях")
        val versionText = TextView(requireContext()).apply {
            text = "ByeByeDPI: ${BuildConfig.VERSION_NAME}\nByeDPI core: 0.17.3 (ba53229)"
            setPadding(0, 16, 0, 16)
            textSize = 12f
        }
        mainContainer.addView(versionText)
    }
    
    private fun addHeader(title: String) {
        val tv = TextView(requireContext()).apply {
            text = title
            textSize = 18f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 24, 0, 8)
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.holo_blue_dark))
        }
        mainContainer.addView(tv)
    }
    
    private fun addDescription(text: String) {
        val tv = TextView(requireContext()).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 4, 0, 8)
        }
        mainContainer.addView(tv)
    }
    
    private fun addCommandExample(title: String, command: String) {
        val titleTv = TextView(requireContext()).apply {
            text = title
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, 16, 0, 4)
        }
        mainContainer.addView(titleTv)
        
        val cmdLayout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        
        val cmdTv = TextView(requireContext()).apply {
            text = command
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val copyBtn = Button(requireContext()).apply {
            text = "Копировать"
            textSize = 10f
            setOnClickListener {
                copyToClipboard(command)
                Toast.makeText(requireContext(), "Скопировано", Toast.LENGTH_SHORT).show()
            }
        }
        
        cmdLayout.addView(cmdTv)
        cmdLayout.addView(copyBtn)
        mainContainer.addView(cmdLayout)
    }
    
    private fun addCodeBlock(code: String) {
        val tv = TextView(requireContext()).apply {
            text = code
            textSize = 12f
            setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
            setPadding(16, 8, 16, 8)
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        mainContainer.addView(tv)
    }
    
    private fun addParam(name: String, description: String) {
        val layout = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }
        
        val nameTv = TextView(requireContext()).apply {
            text = name
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.3f)
        }
        
        val descTv = TextView(requireContext()).apply {
            text = description
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 0.7f)
        }
        
        layout.addView(nameTv)
        layout.addView(descTv)
        mainContainer.addView(layout)
    }
    
    private fun addDivider() {
        val divider = View(requireContext()).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                1
            )
            setBackgroundColor(0xFFCCCCCC.toInt())
            setPadding(0, 16, 0, 16)
        }
        mainContainer.addView(divider)
    }
    
    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("command", text)
        clipboard.setPrimaryClip(clip)
    }
    
    private fun applyStrategy(strategy: String) {
        val prefs = requireContext().getPreferences()
        prefs.edit { putString("byedpi_cmd_args", strategy) }
        
        val mode = if (prefs.getStringNotNull("byedpi_mode", "vpn") == "vpn") Mode.VPN else Mode.Proxy
        ServiceManager.restart(requireContext(), mode)
        
        Toast.makeText(requireContext(), "Стратегия применена, сервис перезапущен", Toast.LENGTH_SHORT).show()
    }
    
    private fun runNativeDirect(args: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val proxy = ByeDpiProxy()
                val fullArgs = arrayOf("ciadpi") + args.split(" ").filter { it.isNotEmpty() }.toTypedArray()
                val result = proxy.javaClass.getDeclaredMethod("jniStartProxy", Array<String>::class.java)
                result.isAccessible = true
                val code = result.invoke(proxy, fullArgs) as Int
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Native запущен, код: $code", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Ошибка: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun resetEverything() {
        val prefs = requireContext().getPreferences()
        prefs.edit { clear() }
        
        val historyUtils = HistoryUtils(requireContext())
        historyUtils.clearAllHistory()
        
        val domainListUtils = io.github.romanvht.byedpi.utility.DomainListUtils
        domainListUtils.resetLists(requireContext())
        
        Toast.makeText(requireContext(), "Полный сброс выполнен. Перезапустите приложение.", Toast.LENGTH_LONG).show()
        
        Handler(Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 2000)
    }
}
