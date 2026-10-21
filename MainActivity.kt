package com.example.pixelcommunique0

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private val nearby by lazy { Nearby.getConnectionsClient(this) }
    private val serviceId = "com.example.pixelcommunique0.PIXEL"
    private val strategy = Strategy.P2P_CLUSTER
    private val connectedEndpoints = ConcurrentHashMap.newKeySet<String>()
    private val pendingFiles = ConcurrentHashMap<Long, android.net.Uri>()
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var lastVoiceFile: File? = null
    private var player: MediaPlayer? = null
    private var scanStarted = false
    private val requestCode = 713

    private fun neededPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) {
            permissions += Manifest.permission.BLUETOOTH_SCAN
            permissions += Manifest.permission.BLUETOOTH_CONNECT
            permissions += Manifest.permission.BLUETOOTH_ADVERTISE
        } else {
            permissions += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) permissions += Manifest.permission.NEARBY_WIFI_DEVICES
        return permissions.toTypedArray()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        setContentView(webView)
        webView.loadUrl("file:///android_asset/index.html")

        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
    }

    inner class AndroidBridge {
        @JavascriptInterface fun startScan() { runOnUiThread { beginDiscoveryAndAdvertising() } }
        @JavascriptInterface fun stopScan() { runOnUiThread { stopDiscoveryAndAdvertising() } }
        @JavascriptInterface fun sendMessage(message: String) {
            val clean = message.trim().take(3000)
            if (clean.isEmpty()) return
            runOnUiThread {
                if (connectedEndpoints.isEmpty()) {
                    js("systemMessage('Aucun téléphone connecté.')")
                } else {
                    val bytes = ("TEXT:" + clean).toByteArray(Charsets.UTF_8)
                    connectedEndpoints.toList().forEach { endpoint ->
                        nearby.sendPayload(endpoint, Payload.fromBytes(bytes))
                            .addOnFailureListener { js("systemMessage('Échec de l’envoi du message.')") }
                    }
                }
            }
        }
        @JavascriptInterface fun startRecording() { runOnUiThread { startVoiceRecording() } }
        @JavascriptInterface fun stopAndSendVoice() { runOnUiThread { stopRecordingAndSend() } }
        @JavascriptInterface fun playLastVoice() { runOnUiThread { playLastVoiceMessage() } }
    }

    private fun beginDiscoveryAndAdvertising() {
        if (scanStarted) return
        val missing = neededPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), requestCode)
            js("systemMessage('Autorise les permissions Android, puis appuie de nouveau sur Signal Fantôme.')")
            return
        }

        scanStarted = true
        connectedEndpoints.clear()
        js("scanState(true)")
        val advertising = AdvertisingOptions.Builder().setStrategy(strategy).build()
        nearby.startAdvertising(Build.MODEL ?: "Android", serviceId, connectionLifecycle, advertising)
            .addOnFailureListener {
                js("systemMessage('La visibilité de cet appareil a échoué.')")
            }
        val discovery = DiscoveryOptions.Builder().setStrategy(strategy).build()
        nearby.startDiscovery(serviceId, discoveryCallback, discovery)
            .addOnFailureListener {
                js("systemMessage('La recherche a échoué.')")
            }
        js("systemMessage('Recherche des appareils à proximité démarrée...')")
    }

    private fun stopDiscoveryAndAdvertising() {
        scanStarted = false
        nearby.stopAdvertising()
        nearby.stopDiscovery()
        js("scanState(false)")
        js("systemMessage('Recherche arrêtée.')")
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            js("systemMessage('Appareil trouvé : ' + ${quote(info.endpointName)})")
            nearby.requestConnection(Build.MODEL ?: "Android", endpointId, connectionLifecycle)
                .addOnFailureListener { js("systemMessage('La demande de connexion a échoué.')") }
        }
        override fun onEndpointLost(endpointId: String) {}
    }

    private val connectionLifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            nearby.acceptConnection(endpointId, payloadCallback)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.isSuccess) {
                connectedEndpoints.add(endpointId)
                js("connected(true)")
                js("systemMessage('Connexion établie avec ' + ${quote(endpointId)} + '.')")
            } else {
                js("systemMessage('La connexion a échoué.')")
            }
        }
        override fun onDisconnected(endpointId: String) {
            connectedEndpoints.remove(endpointId)
            js("connected(" + connectedEndpoints.isNotEmpty() + ")")
            js("systemMessage('Appareil déconnecté.')")
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                val data = payload.asBytes()?.toString(Charsets.UTF_8) ?: return
                if (data.startsWith("TEXT:")) js("receiveMessage(${quote(data.removePrefix("TEXT:"))})")
            } else if (payload.type == Payload.Type.FILE) {
                payload.asFile()?.asUri()?.let { pendingFiles[payload.id] = it }
            }
        }
        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            if (update.status == PayloadTransferUpdate.Status.SUCCESS) {
                val uri = pendingFiles.remove(update.payloadId)
                if (uri != null) {
                    val file = File(cacheDir, "voice_received_${System.currentTimeMillis()}.m4a")
                    try {
                        contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        lastVoiceFile = file
                        js("voiceReceived()")
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun startVoiceRecording() {
        if (connectedEndpoints.isEmpty()) return
        try {
            val file = File(cacheDir, "voice_sent_${System.currentTimeMillis()}.m4a")
            val newRecorder = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
            recorder = newRecorder
            recordingFile = file
            js("recordingChanged(true)")
        } catch (_: Exception) {}
    }

    private fun stopRecordingAndSend() {
        val activeRecorder = recorder ?: return
        val file = recordingFile ?: return
        try {
            activeRecorder.stop()
            activeRecorder.release()
            recorder = null
            recordingFile = null
            lastVoiceFile = file
            js("recordingChanged(false)")
            connectedEndpoints.toList().forEach { endpoint ->
                nearby.sendPayload(endpoint, Payload.fromFile(file))
                    .addOnSuccessListener { js("voiceSent()") }
            }
        } catch (_: Exception) {
            recorder = null
            recordingFile = null
            js("recordingChanged(false)")
        }
    }

    private fun playLastVoiceMessage() {
        val file = lastVoiceFile ?: return
        if (!file.exists()) return
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { it.release(); player = null }
                prepare()
                start()
            }
        } catch (_: Exception) {}
    }

    private fun quote(value: String): String {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "") + "'"
    }

    private fun js(code: String) {
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript(code, null) }
    }

    override fun onDestroy() {
        try { recorder?.stop() } catch (_: Exception) {}
        recorder?.release()
        player?.release()
        nearby.stopAdvertising()
        nearby.stopDiscovery()
        nearby.stopAllEndpoints()
        super.onDestroy()
    }
}
