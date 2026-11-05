package com.mpta.client.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.mpta.client.MptaApp
import com.mpta.client.R
import com.mpta.client.core.DiscoveredHost
import com.mpta.client.core.HostAddress
import com.mpta.client.core.PairingPayload
import com.mpta.client.core.PinPairing
import com.mpta.client.data.Pairing
import com.mpta.client.net.ApiClient
import com.mpta.client.net.ApiException
import com.mpta.client.net.LanDiscovery
import com.mpta.client.net.PinnedHttp
import com.mpta.client.net.TofuProbe
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * QR (CameraX + ML Kit) or pasted payload → POST /api/pair/claim → stored device token (AC-7, AC-19).
 * PIN mode (DESIGN.md §12): LAN discovery or manual address + PIN → TOFU fingerprint → user confirms fpShort →
 * pinned claim → returned fp must equal the observed one.
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var deviceName: EditText
    private lateinit var payloadInput: EditText
    private lateinit var discoverButton: Button
    private lateinit var discoverStatus: TextView
    private lateinit var discoveredList: LinearLayout
    private lateinit var hostAddress: EditText
    private lateinit var pinInput: EditText

    /** Discovery results keyed by the address text put into [hostAddress] when the user picks one. */
    private val discovered = HashMap<String, DiscoveredHost>()

    private lateinit var analysisExecutor: ExecutorService
    private lateinit var scanner: BarcodeScanner
    /** Set while a claim is running so the analyzer stops submitting new payloads. */
    private val busy = AtomicBoolean(false)
    private var cameraProvider: ProcessCameraProvider? = null

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else status.text = getString(R.string.pairing_camera_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        findViewById<android.view.View>(R.id.root).applySystemInsets(includeIme = true)

        status = findViewById(R.id.status)
        previewView = findViewById(R.id.preview)
        deviceName = findViewById(R.id.deviceName)
        payloadInput = findViewById(R.id.payload)
        deviceName.setText(defaultDeviceName())

        analysisExecutor = Executors.newSingleThreadExecutor()
        scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )

        findViewById<Button>(R.id.pasteButton).setOnClickListener {
            val text = payloadInput.text?.toString().orEmpty()
            if (text.isBlank()) {
                status.text = getString(R.string.pairing_paste_hint)
            } else {
                onPayload(text)
            }
        }

        discoverButton = findViewById(R.id.discoverButton)
        discoverStatus = findViewById(R.id.discoverStatus)
        discoveredList = findViewById(R.id.discoveredList)
        hostAddress = findViewById(R.id.hostAddress)
        pinInput = findViewById(R.id.pinInput)
        discoverButton.setOnClickListener { discover() }
        findViewById<Button>(R.id.pinPairButton).setOnClickListener { startPinPairing() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanner.close()
        analysisExecutor.shutdown()
    }

    private fun defaultDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Android" }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                status.text = "카메라를 열 수 없습니다: ${e.message}"
                return@addListener
            }
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy) }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                status.text = getString(R.string.pairing_scanning)
            } catch (e: IllegalArgumentException) {
                status.text = "카메라를 사용할 수 없습니다: ${e.message}"
            } catch (e: IllegalStateException) {
                status.text = "카메라를 사용할 수 없습니다: ${e.message}"
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || busy.get()) {
            proxy.close()
            return
        }
        val image = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
        scanner.process(image)
            .addOnSuccessListener(ContextCompat.getMainExecutor(this)) { barcodes ->
                val raw = barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.contains("\"code\"") } }
                if (raw != null && !busy.get()) onPayload(raw)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun onPayload(text: String) {
        val payload = try {
            PairingPayload.parse(text)
        } catch (e: IllegalArgumentException) {
            status.text = e.message
            return
        }
        if (!busy.compareAndSet(false, true)) return
        status.text = getString(R.string.pairing_in_progress)
        val name = deviceName.text?.toString()?.trim().orEmpty().ifBlank { defaultDeviceName() }

        lifecycleScope.launch {
            try {
                val api = ApiClient(payload.hosts, payload.port, null, PinnedHttp.client(payload.fingerprint))
                val (deviceId, token) = api.claim(payload.code, name)
                val app = application as MptaApp
                // All advertised hosts are kept; HostSelector picks the order per network at connect time.
                app.store.save(Pairing(payload.name, payload.hosts, payload.port, payload.fingerprint, deviceId, token))
                cameraProvider?.unbindAll()
                startActivity(Intent(this@PairingActivity, SessionListActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            } catch (e: java.io.IOException) {
                status.text = "페어링 실패: ${e.message}"
                busy.set(false)
            } catch (e: org.json.JSONException) {
                status.text = "페어링 실패: 서버 응답 형식 오류 (${e.message})"
                busy.set(false)
            }
        }
    }

    // ---- PIN pairing (DESIGN.md §12) ----

    private fun addressKey(host: String, port: Int): String =
        (if (host.contains(':')) "[$host]" else host) + ":" + port

    private fun discover() {
        discoverButton.isEnabled = false
        discoveredList.removeAllViews()
        discovered.clear()
        discoverStatus.visibility = android.view.View.VISIBLE
        discoverStatus.text = getString(R.string.pin_discovering)
        lifecycleScope.launch {
            try {
                val hosts = LanDiscovery.discover(this@PairingActivity)
                if (hosts.isEmpty()) {
                    discoverStatus.text = getString(R.string.pin_discover_none)
                } else {
                    discoverStatus.text = getString(R.string.pin_discover_found, hosts.size)
                    for (h in hosts) {
                        val key = addressKey(h.hosts.first(), h.port)
                        discovered[key] = h
                        val b = Button(this@PairingActivity)
                        b.isAllCaps = false
                        b.text = "${h.name}\n$key · ${h.fpShort}"
                        b.setOnClickListener { hostAddress.setText(key) }
                        discoveredList.addView(b)
                    }
                }
            } catch (e: IOException) {
                discoverStatus.text = getString(R.string.pin_discover_failed, e.message ?: "")
            } finally {
                discoverButton.isEnabled = true
            }
        }
    }

    private fun startPinPairing() {
        val pin = PinPairing.normalizePin(pinInput.text?.toString().orEmpty())
        if (!PinPairing.isValidPin(pin)) {
            status.text = getString(R.string.pin_invalid)
            return
        }
        val addrText = hostAddress.text?.toString()?.trim().orEmpty()
        val addr = HostAddress.parse(addrText)
        if (addr == null) {
            status.text = getString(R.string.pin_address_invalid)
            return
        }
        val disc = discovered[addrText]
        if (!busy.compareAndSet(false, true)) return
        val name = deviceName.text?.toString()?.trim().orEmpty().ifBlank { defaultDeviceName() }
        status.text = getString(R.string.pin_connecting)

        lifecycleScope.launch {
            var paired = false
            try {
                paired = pinPair(addr, disc, pin, name)
            } catch (e: ApiException) {
                status.text = when (e.code) {
                    401 -> getString(R.string.pin_wrong)
                    429 -> getString(R.string.pin_rate_limited)
                    else -> "페어링 실패: ${e.message}"
                }
            } catch (e: IOException) {
                status.text = getString(R.string.pin_connect_failed, e.message ?: "")
            } finally {
                if (!paired) busy.set(false)
            }
        }
    }

    /** Returns true when the pairing was stored and the session list opened. */
    private suspend fun pinPair(addr: HostAddress, disc: DiscoveredHost?, pin: String, deviceName: String): Boolean {
        // 1) TOFU: read the certificate the host presents (typed address first, then the other advertised ones).
        val candidates = (listOf(addr.host) + disc?.hosts.orEmpty()).distinct()
        var observed: String? = null
        var reached: String? = null
        var lastError: IOException? = null
        for (h in candidates) {
            try {
                observed = TofuProbe.fingerprint(h, addr.port)
                reached = h
                break
            } catch (e: IOException) {
                lastError = e
            }
        }
        if (observed == null || reached == null) throw lastError ?: IOException("접속할 호스트가 없습니다")
        val short = PinPairing.fpShort(observed)

        // 2) Discovery advertised an fpShort: a different certificate means another machine answered.
        if (disc != null && !PinPairing.fpShortEquals(disc.fpShort, short)) {
            status.text = getString(R.string.pin_discovery_fp_mismatch, disc.fpShort, short)
            return false
        }

        // 3) The user compares with the PC screen.
        if (!confirmFingerprint(short)) {
            status.text = getString(R.string.pin_cancelled)
            return false
        }

        // 4) Claim over a connection pinned to exactly the confirmed certificate.
        status.text = getString(R.string.pairing_in_progress)
        val http = PinnedHttp.client(observed)
        val claim = ApiClient(listOf(reached), addr.port, null, http).claimPin(pin, deviceName)
        val returnedFp = claim.fingerprint
        if (returnedFp == null) {
            status.text = getString(R.string.pin_claim_fp_missing)
            return false
        }
        if (returnedFp != observed) {
            status.text = getString(R.string.pin_claim_fp_mismatch)
            return false
        }

        // 5) PC name + all advertised hosts (LAN + Tailscale) from /api/info with the new token; best effort.
        var pcName = disc?.name ?: reached
        val hosts = ArrayList<String>().apply {
            add(reached)
            addAll(disc?.hosts.orEmpty())
        }
        try {
            val info = JSONObject(ApiClient(listOf(reached), addr.port, claim.token, http).info())
            pcName = info.optString("name", "").ifBlank { pcName }
            info.optJSONArray("hosts")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val h = arr.optString(i, "").trim()
                    if (h.isNotEmpty()) hosts.add(h)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "info after PIN pairing failed: ${e.message}") // pairing itself succeeded
        } catch (e: JSONException) {
            Log.w(TAG, "info after PIN pairing unreadable: ${e.message}")
        }

        (application as MptaApp).store.save(
            Pairing(pcName, hosts.distinct(), addr.port, observed, claim.deviceId, claim.token)
        )
        cameraProvider?.unbindAll()
        startActivity(Intent(this, SessionListActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        return true
    }

    private suspend fun confirmFingerprint(fpShort: String): Boolean = suspendCancellableCoroutine { cont ->
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pin_confirm_title)
            .setMessage(getString(R.string.pin_confirm_message, fpShort))
            .setCancelable(false)
            .setPositiveButton(R.string.pin_confirm_yes) { _, _ -> if (cont.isActive) cont.resume(true) }
            .setNegativeButton(R.string.pin_confirm_no) { _, _ -> if (cont.isActive) cont.resume(false) }
            .show()
        cont.invokeOnCancellation { dialog.dismiss() }
    }

    private companion object {
        const val TAG = "PairingActivity"
    }
}
