package com.neststudio.pocketshell.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.neststudio.pocketshell.PkshellApp
import com.neststudio.pocketshell.R
import com.neststudio.pocketshell.core.DiscoveredHost
import com.neststudio.pocketshell.core.HostAddress
import com.neststudio.pocketshell.core.PinPairing
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.net.ApiClient
import com.neststudio.pocketshell.net.ApiException
import com.neststudio.pocketshell.net.LanDiscovery
import com.neststudio.pocketshell.net.PinnedHttp
import com.neststudio.pocketshell.net.TofuProbe
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import kotlin.coroutines.resume
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PIN pairing (DESIGN.md §12): LAN discovery or manual address + 6-digit PIN → TOFU fingerprint →
 * user confirms fpShort → pinned POST /api/pair/claim → returned fp must equal the observed one →
 * stored device token (AC-7, AC-19).
 */
class PairingActivity : AppCompatActivity() {

    private lateinit var status: TextView
    private lateinit var deviceName: EditText
    private lateinit var discoverButton: Button
    private lateinit var discoverStatus: TextView
    private lateinit var discoveredList: LinearLayout
    private lateinit var hostAddress: EditText
    private lateinit var pinInput: EditText

    /** Discovery results keyed by the address text put into [hostAddress] when the user picks one. */
    private val discovered = HashMap<String, DiscoveredHost>()

    /** Set while a PIN claim is running so a second tap does not start another one. */
    private val busy = AtomicBoolean(false)

    /**
     * From a scanned QR code or a pocketshell://pair link: the PC's other addresses and its full certificate
     * fingerprint, which then replaces the "compare the fingerprint" question. Cleared when the address is edited.
     */
    private var link: com.neststudio.pocketshell.core.PairLink? = null

    private val scan = registerForActivityResult(com.journeyapps.barcodescanner.ScanContract()) { r ->
        val text = r.contents ?: return@registerForActivityResult // cancelled
        val l = com.neststudio.pocketshell.core.PairLink.parse(text)
        if (l == null) {
            status.text = getString(R.string.qr_not_pocketshell)
            return@registerForActivityResult
        }
        useLink(l)
        startPinPairing() // a scanned code carries everything: connect right away
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pairing)
        WideScreen.limitWidth(findViewById(R.id.content), 560)
        findViewById<android.view.View>(R.id.root).applySystemInsets(includeIme = true)
        findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar).apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationContentDescription(R.string.pcs_back)
            setNavigationOnClickListener { finish() }
        }

        status = findViewById(R.id.status)
        deviceName = findViewById(R.id.deviceName)
        deviceName.setText(defaultDeviceName())

        discoverButton = findViewById(R.id.discoverButton)
        discoverStatus = findViewById(R.id.discoverStatus)
        discoveredList = findViewById(R.id.discoveredList)
        hostAddress = findViewById(R.id.hostAddress)
        pinInput = findViewById(R.id.pinInput)
        discoverButton.setOnClickListener { discover() }
        findViewById<Button>(R.id.pinPairButton).setOnClickListener { startPinPairing() }
        findViewById<Button>(R.id.scanButton).setOnClickListener {
            scan.launch(
                com.journeyapps.barcodescanner.ScanOptions()
                    .setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE)
                    .setPrompt(getString(R.string.qr_scan_prompt))
                    .setBeepEnabled(false)
                    .setOrientationLocked(false),
            )
        }
        // Opened from a pocketshell://pair link (a messenger): fill it in, the user starts it.
        if (savedInstanceState == null) com.neststudio.pocketshell.core.PairLink.parse(intent?.dataString)?.let {
            useLink(it)
            status.text = getString(R.string.qr_link_ready, it.name.ifBlank { it.firstAddress })
        }
    }

    private fun useLink(l: com.neststudio.pocketshell.core.PairLink) {
        link = l
        hostAddress.setText(l.firstAddress)
        pinInput.setText(l.pin)
    }

    private fun defaultDeviceName(): String =
        listOf(Build.MANUFACTURER, Build.MODEL).filter { !it.isNullOrBlank() }.joinToString(" ").ifBlank { "Android" }

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
                        val row = layoutInflater.inflate(R.layout.item_discovered_pc, discoveredList, false)
                        row.findViewById<android.widget.TextView>(R.id.pcName).text = h.name
                        row.findViewById<android.widget.TextView>(R.id.pcAddress).text = "$key · ${h.fpShort}"
                        row.setOnClickListener { hostAddress.setText(key) }
                        discoveredList.addView(row)
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
        // The link applies only while its address is still the one in the field.
        val fromLink = link?.takeIf { it.firstAddress == addrText && it.pin == pin }
        if (!busy.compareAndSet(false, true)) return
        val name = deviceName.text?.toString()?.trim().orEmpty().ifBlank { defaultDeviceName() }
        status.text = getString(R.string.pin_connecting)

        lifecycleScope.launch {
            var paired = false
            try {
                paired = pinPair(addr, disc, pin, name, fromLink)
            } catch (e: ApiException) {
                status.text = when (e.code) {
                    401 -> getString(R.string.pin_wrong)
                    429 -> getString(R.string.pin_rate_limited)
                    else -> ErrorText.of(this@PairingActivity, R.string.err_pairing, e)
                }
            } catch (e: IOException) {
                status.text = getString(R.string.pin_connect_failed, e.message ?: "")
            } finally {
                if (!paired) busy.set(false)
            }
        }
    }

    /** Returns true when the pairing was stored and the session list opened. */
    private suspend fun pinPair(addr: HostAddress, disc: DiscoveredHost?, pin: String, deviceName: String, fromLink: com.neststudio.pocketshell.core.PairLink? = null): Boolean {
        // 1) TOFU: read the certificate the host presents (typed address first, then the other advertised ones).
        val candidates = (listOf(addr.host) + disc?.hosts.orEmpty() + fromLink?.hosts.orEmpty()).distinct()
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

        // 3) The user compares with the PC screen, unless a QR code / link carried the full fingerprint: then it is
        //    checked here, and a different certificate stops the pairing.
        val expected = fromLink?.fingerprint
        if (expected != null) {
            if (expected != observed) {
                status.text = getString(R.string.qr_fp_mismatch)
                return false
            }
        } else if (!confirmFingerprint(short)) {
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
            addAll(fromLink?.hosts.orEmpty())
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

        val app = application as PkshellApp
        // Re-pairing a PC that is already on the list keeps the alias the user gave it. A share PIN pairs this phone
        // with one session only (the session list then opens it).
        val fresh = Pairing(pcName, hosts.distinct(), addr.port, observed, claim.deviceId, claim.token, share = claim.share)
        val pairing = fresh.copy(alias = app.store.get(fresh.id)?.alias.orEmpty())
        app.store.save(pairing) // adds this PC, or replaces it when re-pairing
        app.select(pairing.id)
        // Opened from the PC list, which stays below: back from the sessions returns to it.
        startActivity(Intent(this, SessionListActivity::class.java))
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
