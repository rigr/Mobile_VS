package de.logicvs.bridge

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.midi.*
import android.net.wifi.WifiManager
import android.os.*
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

class BridgeService : Service() {
    private var server: BridgeHttpServer? = null
    private var midiManager: MidiManager? = null
    private val opened = ConcurrentHashMap<Int, MidiDevice>()
    private val sendPorts = ConcurrentHashMap<String, MidiInputPort>()
    private val receiveDevices = ConcurrentHashMap<String, MidiOutputPort>()
    private val wakeLock by lazy { (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LogicVS:Bridge") }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(7, notification("Starte Netzwerk- und MIDI-Dienst"))
        if (!wakeLock.isHeld) wakeLock.acquire(12 * 60 * 60 * 1000L)
        midiManager = getSystemService(MIDI_SERVICE) as MidiManager
        refreshMidi()
        midiManager?.registerDeviceCallback(object : MidiManager.DeviceCallback() {
            override fun onDeviceAdded(info: MidiDeviceInfo) = refreshMidi()
            override fun onDeviceRemoved(info: MidiDeviceInfo) { closeDevice(info.id); if (BridgeState.selectedDeviceId == info.id) BridgeState.selectedDeviceId = null; refreshMidi() }
        }, Handler(Looper.getMainLooper()))
        server = BridgeHttpServer(this).also { try { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false); BridgeState.status.set("Server läuft") } catch (e: Exception) { BridgeState.status.set("Serverfehler: ${e.message}") } }
        updateNotification()
    }

    fun refreshMidi() {
        val list = midiManager?.devices?.map { info ->
            val props = info.properties
            val name = (props.getString(MidiDeviceInfo.PROPERTY_NAME) ?: props.getString(MidiDeviceInfo.PROPERTY_PRODUCT) ?: "MIDI-Gerät ${info.id}")
            BridgeState.MidiDeviceInfo(info.id, name, info.inputPortCount, info.outputPortCount)
        } ?: emptyList()
        BridgeState.devices.set(list)
        if (BridgeState.selectedDeviceId == null && list.isNotEmpty()) selectDevice(list.first().id)
    }

    @Synchronized fun selectDevice(id: Int, inputPortIndex: Int = 0) {
        val info = midiManager?.devices?.firstOrNull { it.id == id } ?: return
        opened.keys.filter { it != id }.toList().forEach { closeDevice(it) }
        closeDevice(id)
        midiManager?.openDevice(info, { device ->
            if (device == null) { BridgeState.status.set("MIDI-Gerät konnte nicht geöffnet werden"); return@openDevice }
            opened[id] = device
            BridgeState.selectedDeviceId = id
            // Open every port that accepts data from the app (USB-MIDI OUT direction).
            repeat(info.inputPortCount) { portIndex ->
                device.openInputPort(portIndex)?.let { sendPorts["$id:$portIndex"] = it }
            }
            // All device output ports are forwarded to the browser as MIDI input.
            repeat(info.outputPortCount) { portIndex ->
                val output = device.openOutputPort(portIndex)
                if (output != null) {
                    output.connect(object : MidiReceiver() {
                        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                            val hex = msg.copyOfRange(offset, offset + count).joinToString(" ") { "%02X".format(it) }
                            BridgeState.received.offer("$id:$portIndex|$hex")
                            while (BridgeState.received.size > 512) BridgeState.received.poll()
                        }
                    })
                    receiveDevices["$id:$portIndex"] = output
                }
            }
            BridgeState.status.set("MIDI verbunden: ${BridgeState.devices.get().firstOrNull { it.id == id }?.name ?: id}")
            updateNotification()
        }, Handler(Looper.getMainLooper()))
    }

    fun sendMidi(deviceId: Int, portIndex: Int, hex: String): Boolean = try {
        val bytes = hex.trim().split(Regex("\\s+|,|;")).filter { it.isNotBlank() }.map { it.toInt(16).toByte() }.toByteArray()
        val port = sendPorts["$deviceId:$portIndex"] ?: return false
        port.send(bytes, 0, bytes.size)
        true
    } catch (_: Exception) { false }

    private fun closeDevice(id: Int) { sendPorts.keys.filter { it.startsWith("$id:") }.forEach { try { sendPorts.remove(it)?.close() } catch (_: Exception) {} }; receiveDevices.keys.filter { it.startsWith("$id:") }.forEach { try { receiveDevices.remove(it)?.close() } catch (_: Exception) {} }; try { opened.remove(id)?.close() } catch (_: Exception) {} }
    private fun createChannel() { if (Build.VERSION.SDK_INT >= 26) { val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(NotificationChannel("logicvs", "Logic VS Bridge", NotificationManager.IMPORTANCE_LOW)) } }
    private fun notification(msg: String): Notification = NotificationCompat.Builder(this, "logicvs").setContentTitle("Logic VS Bridge").setContentText(msg).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
    private fun updateNotification() { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(7, notification("${localUrl()} · ${BridgeState.status.get()}")) }
    fun localUrl(): String = "http://${localIp()}:8080"
    private fun localIp(): String = try { NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }.first { !it.isLoopbackAddress && it is Inet4Address }.hostAddress ?: "127.0.0.1" } catch (_: Exception) { "127.0.0.1" }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { intent?.takeIf { it.hasExtra("select") }?.getIntExtra("select", -1)?.takeIf { it >= 0 }?.let { selectDevice(it) }; return START_STICKY }
    override fun onDestroy() { server?.stop(); opened.keys.toList().forEach { closeDevice(it) }; if (wakeLock.isHeld) wakeLock.release(); super.onDestroy() }
    override fun onBind(intent: Intent?) = null
}

class BridgeHttpServer(private val service: BridgeService) : NanoHTTPD(8080) {
    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        return when {
            session.method == Method.OPTIONS -> newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            path == "/api/status" -> json(JSONObject().put("status", BridgeState.status.get()).put("url", service.localUrl()).put("devices", JSONArray(BridgeState.devices.get().map { JSONObject().put("id", it.id).put("name", it.name).put("inputs", it.inputs).put("outputs", it.outputs) })))
            path == "/api/midi/select" && session.method == Method.POST -> {
                val files = HashMap<String, String>(); session.parseBody(files); val body = files["postData"] ?: "{}"
                val obj = JSONObject(body); val id = obj.optInt("id", -1); val port = obj.optInt("port", 0); service.selectDevice(id, port); json(JSONObject().put("ok", id >= 0).put("id", id).put("port", port))
            }
            path == "/api/midi/poll" -> { val arr = JSONArray(); repeat(64) { BridgeState.received.poll()?.let { arr.put(it) } ?: return@repeat }; json(JSONObject().put("messages", arr)) }
            path == "/api/midi/send" && session.method == Method.POST -> {
                val files = HashMap<String, String>(); session.parseBody(files); val body = files["postData"] ?: "{}"
                val obj = JSONObject(body); val hex = obj.optString("hex"); val id = obj.optInt("id", -1); val port = obj.optInt("port", 0); json(JSONObject().put("ok", service.sendMidi(id, port, hex)))
            }
            path == "/" || path == "/index.html" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", assets("www/index.html"))
            path.startsWith("/assets/") -> newFixedLengthResponse(Response.Status.OK, "application/octet-stream", assets("www/" + path.removePrefix("/assets/")))
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }.apply { addHeader("Access-Control-Allow-Origin", "*"); addHeader("Cache-Control", "no-store") }
    }
    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())
    private fun assets(path: String): String = service.assets.open(path).bufferedReader().use { it.readText() }
}
