package com.erkam.tvdisk

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.erkam.tvdisk.usb.UsbMassStorage

class MainActivity : Activity() {

    private lateinit var usb: UsbManager
    private lateinit var dot: View
    private lateinit var statusText: TextView
    private lateinit var statusSub: TextView

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { dot.postDelayed({ refresh() }, 400) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        usb = getSystemService(Context.USB_SERVICE) as UsbManager
        dot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        statusSub = findViewById(R.id.statusSub)
        findViewById<View>(R.id.btnFormat).setOnClickListener {
            startActivity(Intent(this, FormatActivity::class.java))
        }
        findViewById<View>(R.id.btnVideo).setOnClickListener {
            startActivity(Intent(this, VideoActivity::class.java))
        }
        findViewById<View>(R.id.btnWifi).setOnClickListener { chooseWifiRole() }
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        registerReceiverCompat(usbReceiver, f)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) { }
    }

    private fun chooseWifiRole() {
        val hasDisk = UsbMassStorage.find(usb) != null
        val items = arrayOf(
            "Bu telefon ALSIN" + (if (hasDisk) "  (flash disk burada takılı)" else "\nFlash disk bu telefona takılı olmalı"),
            "Bu telefon GÖNDERSİN\nVideolar bu telefonda",
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Kablosuz Aktar")
            .setItems(items) { _, which ->
                startActivity(Intent(this, if (which == 0) ReceiveActivity::class.java else SendActivity::class.java))
            }
            .show()
    }

    private fun refresh() {
        val dev = UsbMassStorage.find(usb)
        if (dev != null) {
            val name = (if (Build.VERSION.SDK_INT >= 21) dev.productName else null)?.trim().takeUnless { it.isNullOrEmpty() }
                ?: "USB bellek"
            statusText.text = "Flash disk takılı"
            statusSub.text = name
            dot.backgroundTintList = ColorStateList.valueOf(color(R.color.ok))
        } else {
            statusText.text = "Flash disk takılı değil"
            statusSub.text = "Diski telefonun şarj girişine tak"
            dot.backgroundTintList = ColorStateList.valueOf(color(R.color.sub))
        }
    }
}
