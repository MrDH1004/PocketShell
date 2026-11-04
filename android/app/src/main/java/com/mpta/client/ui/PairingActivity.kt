package com.mpta.client.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.mpta.client.core.PairingPayload
import com.mpta.client.data.Pairing
import com.mpta.client.net.ApiClient
import com.mpta.client.net.PinnedHttp
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** QR (CameraX + ML Kit) or pasted payload → POST /api/pair/claim → stored device token (AC-7, AC-19). */
class PairingActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var previewView: PreviewView
    private lateinit var deviceName: EditText
    private lateinit var payloadInput: EditText

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
                // Keep the payload order (LAN first, then Tailscale) for all later connections.
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
}
