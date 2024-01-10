package com.yeo.develop

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.yeo.develop.obdlibrary.bluetooth.connector.OBDConnectionManager
import com.yeo.develop.obdlibrary.calculator.SpeedDistanceCalculator
import com.yeo.develop.obdlibrary.playground.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch


@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private val binding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OBDConnectionManager.init(this)

        setContentView(binding.root)
        initUi()
    }

    private fun initUi() {
        lifecycleScope.launch {
            launch {
                OBDConnectionManager.bluetoothConnectionState.collectLatest {
                    binding.tvBluetoothConnectState.text = it.toString()
                }
            }
            launch {
                SpeedDistanceCalculator.distance.collectLatest {
                    binding.tvCurrentDistance.text = it.toString()
                }
            }
        }
        binding.edtMacAddress.setText("10:21:3E:48:16:76")
        binding.btnConnect.setOnClickListener {
            OBDConnectionManager.connect(binding.edtMacAddress.text.toString())
        }
        binding.btnDisconnect.setOnClickListener {
            OBDConnectionManager.disconnect()
        }
        binding.btnStartDistance.setOnClickListener {
            SpeedDistanceCalculator.beginDistanceCalculation()
        }
        binding.btnStopDistance.setOnClickListener {
            SpeedDistanceCalculator.endDistanceCalculation()
        }
        binding.btnPauseDistance.setOnClickListener {
            SpeedDistanceCalculator.pause()
        }
        binding.btnResumeDistance.setOnClickListener {
            SpeedDistanceCalculator.resume()
        }
    }
}