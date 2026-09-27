package com.example.blechat

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.example.blechat.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity(), BleChatManager.Listener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ble: BleChatManager

    private val peerLabels = mutableListOf<String>()
    private val peerAddresses = mutableListOf<String>()
    private lateinit var peerAdapter: ArrayAdapter<String>
    private var running = false

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val REQ_ENABLE_BT = 2
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ble = BleChatManager(this, this)
        binding.deviceNameEdit.setText(Build.MODEL ?: "")

        peerAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, peerLabels)
        binding.peersList.adapter = peerAdapter
        binding.peersList.setOnItemClickListener { _, _, position, _ ->
            val address = peerAddresses[position]
            appendLog("Connecting to $address…")
            ble.connect(address)
        }

        binding.startButton.setOnClickListener {
            if (!running) {
                if (!hasPermissions()) {
                    requestBlePermissions()
                    return@setOnClickListener
                }
                if (!ble.isBluetoothOn()) {
                    @Suppress("DEPRECATION")
                    startActivityForResult(
                        Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE),
                        REQ_ENABLE_BT
                    )
                    return@setOnClickListener
                }
                startChat()
            } else {
                stopChat()
            }
        }

        binding.sendButton.setOnClickListener {
            val text = binding.messageEdit.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            if (ble.sendMessage(text)) {
                appendLog("Me: $text")
                binding.messageEdit.text.clear()
            } else {
                Toast.makeText(this, "Not connected to a peer yet", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startChat() {
        running = true
        binding.startButton.text = "Stop"
        binding.deviceNameEdit.isEnabled = false
        peerLabels.clear()
        peerAddresses.clear()
        peerAdapter.notifyDataSetChanged()
        ble.start(binding.deviceNameEdit.text.toString().trim())
    }

    private fun stopChat() {
        running = false
        binding.startButton.text = "Start"
        binding.deviceNameEdit.isEnabled = true
        ble.stop()
    }

    // ---------------- Permissions ----------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasPermissions(): Boolean =
        requiredPermissions().all {
            ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestBlePermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            if (!ble.isBluetoothOn()) {
                @Suppress("DEPRECATION")
                startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_ENABLE_BT)
            } else {
                startChat()
            }
        } else if (requestCode == REQ_PERMISSIONS) {
            Toast.makeText(this, "Bluetooth permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Using onActivityResult for the BT enable prompt")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ENABLE_BT) {
            if (ble.isBluetoothOn()) startChat()
            else Toast.makeText(this, "Bluetooth is required", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------- BleChatManager.Listener ----------------

    override fun onStatus(text: String) = runOnUiThread {
        binding.statusText.text = text
    }

    override fun onPeerFound(name: String, address: String) = runOnUiThread {
        if (!peerAddresses.contains(address)) {
            peerAddresses.add(address)
            peerLabels.add("$name\n$address")
            peerAdapter.notifyDataSetChanged()
        }
    }

    override fun onConnectionChanged(address: String, connected: Boolean) = runOnUiThread {
        appendLog(if (connected) "Connected: $address" else "Disconnected: $address")
    }

    override fun onMessageReceived(sender: String, text: String) = runOnUiThread {
        appendLog("$sender: $text")
    }

    private fun appendLog(line: String) {
        binding.chatLog.append(line + "\n")
        binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        if (running) ble.stop()
        super.onDestroy()
    }
}
