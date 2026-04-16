package io.github.romanvht.byedpi.activities

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import io.github.romanvht.byedpi.R
import io.github.romanvht.byedpi.data.*
import io.github.romanvht.byedpi.databinding.ActivityTvMainBinding
import io.github.romanvht.byedpi.services.ServiceManager
import io.github.romanvht.byedpi.services.appStatus
import io.github.romanvht.byedpi.utility.*

class TvMainActivity : FragmentActivity() {
    private lateinit var binding: ActivityTvMainBinding

    companion object {
        private val TAG = TvMainActivity::class.java.simpleName
    }

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                ServiceManager.start(this, Mode.VPN)
            } else {
                Toast.makeText(this, R.string.vpn_permission_denied, Toast.LENGTH_SHORT).show()
                updateUI()
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Broadcast received: ${intent?.action}")
            when (intent?.action) {
                STARTED_BROADCAST, STOPPED_BROADCAST, FAILED_BROADCAST -> updateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
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

        binding.toggleButton.setOnClickListener {
            val (status, _) = appStatus
            when (status) {
                AppStatus.Halted -> start()
                AppStatus.Running -> stop()
            }
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        // Request focus on toggle button by default
        binding.toggleButton.requestFocus()

        updateUI()
    }

    override fun onResume() {
        super.onResume()
        updateUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
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

    private fun updateUI() {
        val (status, mode) = appStatus
        val preferences = getPreferences()
        val (ip, port) = preferences.getProxyIpAndPort()

        binding.proxyAddress.text = getString(R.string.proxy_address, ip, port)

        when (status) {
            AppStatus.Halted -> {
                binding.statusText.text = when (preferences.mode()) {
                    Mode.VPN -> getString(R.string.vpn_disconnected)
                    Mode.Proxy -> getString(R.string.proxy_down)
                }
                binding.toggleButton.text = getString(R.string.service_start_btn)
                binding.statusIndicator.setBackgroundResource(R.drawable.status_indicator_off)
            }
            AppStatus.Running -> {
                binding.statusText.text = when (mode) {
                    Mode.VPN -> getString(R.string.vpn_connected)
                    Mode.Proxy -> getString(R.string.proxy_up)
                }
                binding.toggleButton.text = getString(R.string.service_stop_btn)
                binding.statusIndicator.setBackgroundResource(R.drawable.status_indicator_on)
            }
        }
    }
}
