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

    /** One client per PC (OkHttp client shared with its WebSockets), rebuilt when that PC's pairing changes. */
    private val apis = HashMap<String, Pair<Pairing, ApiClient>>()

    override fun onCreate() {
        super.onCreate()
        store = PairingStore(this)
    }

    /** Every paired PC, in the order they were added. */
    fun pcs(): List<Pairing> = store.all()

    /** Makes [id] the open PC: session list, terminal and files all talk to it. */
    fun select(id: String) {
        store.activeId = id
    }

    /** The open PC, or null when none is selected. */
    fun pairing(): Pairing? = store.load()

    /** API client for the open PC. */
    fun api(): ApiClient? = pairing()?.let(::apiFor)

    @Synchronized
    fun apiFor(p: Pairing): ApiClient {
        val cached = apis[p.id]
        if (cached != null && cached.first == p) return cached.second
        val selector = HostSelector(p.hosts) { NetworkState.snapshot(this) }
        val api = ApiClient(selector, p.port, p.token, PinnedHttp.client(p.fingerprint))
        apis[p.id] = p to api
        return api
    }

    /** Forgets [id] (default: the open PC). */
    @Synchronized
    fun unpair(id: String? = store.activeId) {
        if (id == null) return
        store.remove(id)
        apis.remove(id)
    }
}
