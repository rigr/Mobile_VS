package de.logicvs.bridge

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix

class MainActivity : Activity() {
    private lateinit var urlView: TextView
    private lateinit var statusView: TextView
    private lateinit var qrView: ImageView
    private lateinit var deviceSpinner: Spinner
    private var dimmed = false
    private val refresh = object : Runnable { override fun run() { refreshUi(); window.decorView.postDelayed(this, 2000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
        startBridge()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(24, 24, 24, 24) }
        root.addView(TextView(this).apply { text = "LOGIC VS BRIDGE"; textSize = 24f; gravity = Gravity.CENTER; setPadding(0, 4, 0, 12) })
        statusView = TextView(this).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 8) }; root.addView(statusView)
        qrView = ImageView(this).apply { adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER }
        root.addView(qrView, LinearLayout.LayoutParams(280, 280))
        urlView = TextView(this).apply { textSize = 18f; gravity = Gravity.CENTER; setPadding(0, 8, 0, 16) }; root.addView(urlView)
        root.addView(TextView(this).apply { text = "USB-MIDI-Gerät"; textSize = 14f })
        deviceSpinner = Spinner(this); root.addView(deviceSpinner, LinearLayout.LayoutParams(-1, -2))
        deviceSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) { BridgeState.devices.get().getOrNull(position)?.let { sendSelect(it.id) } }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(Button(this).apply { text = "Display dimmen"; setOnClickListener { toggleDim() } })
        row.addView(Button(this).apply { text = "Dienst neu starten"; setOnClickListener { stopService(Intent(this@MainActivity, BridgeService::class.java)); startBridge() } })
        root.addView(row)
        root.addView(TextView(this).apply { text = "Ohne SIM und ohne Internet nutzbar. Smartphone und Bediengerät müssen im selben WLAN sein."; textSize = 12f; gravity = Gravity.CENTER; setPadding(8, 18, 8, 0) })
        setContentView(root)
        window.decorView.post(refresh)
    }

    private fun startBridge() { val i = Intent(this, BridgeService::class.java); ContextCompat.startForegroundService(this, i) }
    private fun refreshUi() {
        val url = "http://${localIp()}:8080"
        urlView.text = url
        statusView.text = BridgeState.status.get()
        try { qrView.setImageBitmap(makeQr(url)) } catch (_: Exception) {}
        val names = BridgeState.devices.get().map { "${it.name}  (App→Gerät: ${it.inputs} · Gerät→App: ${it.outputs})" }
        val old = deviceSpinner.adapter as? ArrayAdapter<String>
        if (old == null || (0 until old.count).map { old.getItem(it) } != names) deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
    }
    private fun sendSelect(id: Int) { BridgeState.selectedDeviceId = id; val i = Intent(this, BridgeService::class.java).putExtra("select", id); startService(i) }
    private fun makeQr(value: String): Bitmap { val m: BitMatrix = MultiFormatWriter().encode(value, BarcodeFormat.QR_CODE, 500, 500); val pixels = IntArray(500 * 500); for (y in 0 until 500) for (x in 0 until 500) pixels[y * 500 + x] = if (m[x, y]) 0xFF000000.toInt() else -1; return Bitmap.createBitmap(pixels, 500, 500, Bitmap.Config.ARGB_8888) }
    private fun localIp(): String = try { java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.first { !it.isLoopbackAddress && it is java.net.Inet4Address }.hostAddress ?: "127.0.0.1" } catch (_: Exception) { "127.0.0.1" }
    private fun toggleDim() { dimmed = !dimmed; window.attributes = window.attributes.apply { screenBrightness = if (dimmed) 0.015f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }; window.decorView.systemUiVisibility = if (dimmed) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else View.SYSTEM_UI_FLAG_VISIBLE }
    override fun onDestroy() { window.decorView.removeCallbacks(refresh); super.onDestroy() }
}
