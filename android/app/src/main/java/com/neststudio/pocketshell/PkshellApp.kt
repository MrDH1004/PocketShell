package com.neststudio.pocketshell

import android.app.Application
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.data.PairingStore
import com.neststudio.pocketshell.net.ApiClient
import com.neststudio.pocketshell.net.HostSelector
import com.neststudio.pocketshell.net.NetworkState
import com.neststudio.pocketshell.net.PinnedHttp

class PkshellApp : Application() {
    lateinit var store: PairingStore
        private set

    private var cachedPairing: Pairing? = null
    private var cachedApi: ApiClient? = null

    override fun onCreate() {
        super.onCreate()
        store = PairingStore(this)
    }

    fun pairing(): Pairing? = store.load()

    /** API client for the stored pairing (one OkHttp client per pairing, shared with WebSockets). */
    fun api(): ApiClient? {
        val p = store.load() ?: return null
        if (p != cachedPairing || cachedApi == null) {
            cachedPairing = p
            val selector = HostSelector(p.hosts) { NetworkState.snapshot(this) }
            cachedApi = ApiClient(selector, p.port, p.token, PinnedHttp.client(p.fingerprint))
        }
        return cachedApi
    }

    fun unpair() {
        store.clear()
        cachedPairing = null
        cachedApi = null
    }
}
