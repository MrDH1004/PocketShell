package com.neststudio.pocketshell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.neststudio.pocketshell.core.AgentState
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.core.SessionSections
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.net.ApiClient
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException
import javax.swing.SwingUtilities

val Bg = Color(0xFF0E1426)
val Panel = Color(0xFF18213C)
val PanelHi = Color(0xFF243054)
val Gold = Color(0xFFE8C478)
val Muted = Color(0xFF96A0B9)
val Danger = Color(0xFFE57373)

private val scheme = darkColorScheme(
    primary = Gold, onPrimary = Color(0xFF1A1A1A), background = Bg, surface = Panel, onSurface = Color(0xFFE6E9F0),
    onBackground = Color(0xFFE6E9F0), secondary = Muted, surfaceVariant = PanelHi, error = Danger,
)

fun main() {
    System.setProperty("apple.awt.application.name", "PocketShell")
    ApiClient.warn = { msg, e -> Log.warn("$msg: ${e.message}") }
    Log.info("PocketShell desktop ${Updater.current} on ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
    application {
        val state = rememberWindowState(size = DpSize(980.dp, 680.dp))
        Window(onCloseRequest = ::exitApplication, state = state, title = "PocketShell", icon = painterResource("icon.png")) {
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize(), color = Bg) { App() }
            }
        }
    }
}

/** Open terminal windows by "pc id / session id": opening the same session again brings its window forward. */
private val openWindows = HashMap<String, TerminalWindow>()

@Composable
fun App() {
    val store = remember { Store() }
    var pcs by remember { mutableStateOf(store.pairings()) }
    var prefs by remember { mutableStateOf(store.prefs()) }
    var selectedId by remember { mutableStateOf(prefs.activeId?.takeIf { id -> pcs.any { it.id == id } } ?: pcs.firstOrNull()?.id) }
    var showPair by remember { mutableStateOf(pcs.isEmpty()) }
    var showSettings by remember { mutableStateOf(false) }
    var showNew by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<DesktopRelease?>(null) }
    val selected = pcs.firstOrNull { it.id == selectedId }

    LaunchedEffect(Unit) {
        while (true) {
            try { update = Updater.check() } catch (e: IOException) { Log.warn("update check: ${e.message}") }
            delay(6 * 60 * 60 * 1000L)
        }
    }

    fun select(id: String?) {
        selectedId = id
        prefs = prefs.copy(activeId = id)
        store.savePrefs(prefs)
    }

    Row(Modifier.fillMaxSize()) {
        Sidebar(
            pcs, selectedId, onSelect = ::select, onPair = { showPair = true }, onSettings = { showSettings = true },
            onRemove = { p ->
                store.remove(p.id)
                pcs = store.pairings()
                if (selectedId == p.id) select(pcs.firstOrNull()?.id)
            },
        )
        Column(Modifier.fillMaxSize()) {
            update?.let { UpdateBanner(it) }
            if (selected == null) Empty(onPair = { showPair = true })
            else Sessions(selected, prefs.fontSize, onNew = { showNew = true }, onSeen = { hosts ->
                // Addresses the PC advertises now (Tailscale added later, new Wi-Fi) join the stored list.
                val merged = (selected.hosts + hosts).distinct()
                if (merged != selected.hosts) {
                    store.save(selected.copy(hosts = merged))
                    pcs = store.pairings()
                }
            })
        }
    }

    if (showPair) PairDialog(prefs.deviceName, onClose = { showPair = false }) { p ->
        val keep = store.pairings().firstOrNull { it.id == p.id }?.alias.orEmpty()
        store.save(p.copy(alias = keep))
        pcs = store.pairings()
        select(p.id)
        showPair = false
    }
    if (showSettings) SettingsDialog(prefs, update, onClose = { showSettings = false }, onUpdate = { update = it }) {
        prefs = it
        store.savePrefs(it)
    }
    if (showNew && selected != null) NewSessionDialog(selected, onClose = { showNew = false }) { s ->
        showNew = false
        openTerminal(selected, s, prefs.fontSize)
    }
}

fun openTerminal(pc: Pairing, s: SessionInfo, fontSize: Int) {
    val key = pc.id + "/" + s.id
    SwingUtilities.invokeLater {
        openWindows[key]?.let { if (it.bringToFront()) return@invokeLater }
        val w = TerminalWindow(pc, s, fontSize) { openWindows.remove(key) }
        openWindows[key] = w
        w.show()
    }
}

@Composable
private fun Sidebar(
    pcs: List<Pairing>, selectedId: String?, onSelect: (String) -> Unit, onPair: () -> Unit, onSettings: () -> Unit,
    onRemove: (Pairing) -> Unit,
) {
    Column(Modifier.width(240.dp).fillMaxHeight().background(Panel).padding(12.dp)) {
        Text("PocketShell", fontWeight = FontWeight.SemiBold, fontSize = 18.sp, modifier = Modifier.padding(8.dp, 6.dp, 8.dp, 14.dp))
        Text("PC", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(pcs, key = { it.id }) { p ->
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (p.id == selectedId) PanelHi else Color.Transparent)
                        .clickable { onSelect(p.id) }.padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (p.id == selectedId) FontWeight.SemiBold else FontWeight.Normal)
                        Text(p.hosts.firstOrNull().orEmpty(), color = Muted, fontSize = 11.sp, maxLines = 1)
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.MoreVert, "메뉴", tint = Muted) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("이 PC 목록에서 제거") }, onClick = { menu = false; onRemove(p) })
                        }
                    }
                }
            }
        }
        TextButton(onClick = onPair) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("PC 연결") }
        TextButton(onClick = onSettings) { Icon(Icons.Default.Settings, null); Spacer(Modifier.width(6.dp)); Text("설정") }
    }
}

@Composable
private fun Empty(onPair: () -> Unit) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("연결된 PC 없음", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("PC 트레이의 '휴대폰 연결'에 나오는 PIN으로 연결", color = Muted)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onPair) { Text("PC 연결") }
    }
}

@Composable
private fun UpdateBanner(r: DesktopRelease) {
    Row(Modifier.fillMaxWidth().background(PanelHi).clickable { Browser.open(r.url) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("새 버전 v${r.version}", color = Gold, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.width(10.dp))
        Text("눌러서 내려받기 → 열어서 응용 프로그램 폴더로 끌어 놓기", color = Muted, fontSize = 13.sp)
    }
}

/** Running (PocketShell's and the PC's own consoles), starred folders, past sessions of one PC; refreshed every 5 s. */
@Composable
private fun Sessions(pc: Pairing, fontSize: Int, onNew: () -> Unit, onSeen: (List<String>) -> Unit) {
    val api = remember(pc) { Apis.of(pc) }
    var sections by remember(pc.id) { mutableStateOf<SessionSections?>(null) }
    var favorites by remember(pc.id) { mutableStateOf<List<String>>(emptyList()) }
    var error by remember(pc.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val focused = LocalWindowInfo.current.isWindowFocused

    suspend fun load() {
        try {
            val (all, favs) = api.sessionList()
            favorites = favs
            sections = SessionSections.of(all, favorites = favs)
            error = null
        } catch (e: IOException) {
            error = "PC에 연결할 수 없음: ${e.message}"
        } catch (e: JSONException) {
            error = "PC 응답을 읽을 수 없음: ${e.message}"
        }
    }

    LaunchedEffect(pc.id) {
        try {
            val info = org.json.JSONObject(api.info())
            info.optJSONArray("hosts")?.let { a -> onSeen((0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }) }
        } catch (e: IOException) { } catch (e: JSONException) { }
    }
    LaunchedEffect(pc.id, focused, tick) {
        load()
        while (focused) {
            delay(5000)
            load()
        }
    }

    fun act(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() } catch (e: IOException) { error = e.message } catch (e: JSONException) { error = e.message } finally { busy = false }
            tick++
        }
    }

    fun open(s: SessionInfo) = act {
        val target = when {
            s.isFavoriteStub -> api.createSession(null, null, null, null, cwd = s.cwd)
            s.isHistory -> api.resume(s.id)
            else -> s
        }
        openTerminal(pc, target, fontSize)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(pc.displayName, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(pc.hosts.joinToString(", ") + " · 포트 ${pc.port}", color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "새로 고침", tint = Muted) }
            Button(onClick = onNew, enabled = pc.share == null, colors = ButtonDefaults.buttonColors(containerColor = Gold)) { Text("새 세션") }
        }
        error?.let { Text(it, color = Danger, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }
        val sec = sections
        if (sec == null) {
            Text(if (error == null) "불러오는 중…" else "", color = Muted, modifier = Modifier.padding(20.dp))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            val running = sec.running + sec.external
            section("실행 중", running, favorites, ::open, ::act, api)
            section("즐겨찾기", sec.favorites, favorites, ::open, ::act, api)
            section("최근", sec.recent, favorites, ::open, ::act, api)
            if (running.isEmpty() && sec.favorites.isEmpty() && sec.recent.isEmpty()) item { Text("세션 없음. '새 세션'으로 시작", color = Muted, modifier = Modifier.padding(12.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, list: List<SessionInfo>, favorites: List<String>, open: (SessionInfo) -> Unit, act: (suspend () -> Unit) -> Unit, api: ApiClient,
) {
    if (list.isEmpty()) return
    item(key = "h-$title") { Text(title, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp, top = 14.dp, bottom = 4.dp)) }
    items(list, key = { "$title-${it.id}" }) { s -> SessionRow(s, SessionSections.isFavorite(favorites, s.cwd), { open(s) }, act, api) }
}

@Composable
private fun SessionRow(s: SessionInfo, starred: Boolean, onOpen: () -> Unit, act: (suspend () -> Unit) -> Unit, api: ApiClient) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp)).background(Panel).clickable(onClick = onOpen)
            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.displayTitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (s.isExternal) Badge("PC", Muted)
                agentLabel(s)?.let { (t, c) -> Badge(t, c) }
                if (starred) Text("  ★", color = Gold, fontSize = 12.sp)
            }
            Text(listOfNotNull(s.cwd.ifBlank { null }, s.agent).joinToString(" · "), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "메뉴", tint = Muted) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("열기") }, onClick = { menu = false; onOpen() })
                if (s.cwd.isNotBlank()) DropdownMenuItem(text = { Text(if (starred) "즐겨찾기 해제" else "즐겨찾기") }, onClick = {
                    menu = false
                    act { api.setFavorite(s.cwd, !starred) }
                })
                if (s.isRunningHost) DropdownMenuItem(text = { Text("세션 종료", color = Danger) }, onClick = { menu = false; act { api.end(s.id) } })
                if (s.kind == SessionInfo.KIND_HISTORY || (s.kind == SessionInfo.KIND_HOST && !s.isRunningHost)) {
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text("기록 삭제", color = Danger) }, onClick = { menu = false; act { api.delete(s.id) } })
                }
            }
        }
    }
}

private fun agentLabel(s: SessionInfo): Pair<String, Color>? = when (s.agentState) {
    AgentState.WORKING -> "작업 중" to Color(0xFF64B5F6)
    AgentState.WAITING -> "응답 대기" to Gold
    AgentState.DONE -> "완료" to Color(0xFF81C784)
    else -> null
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 11.sp,
        modifier = Modifier.padding(start = 8.dp).clip(RoundedCornerShape(4.dp)).background(color.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

object Browser {
    fun open(url: String) {
        try {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        } catch (e: IOException) {
            Log.warn("open $url: ${e.message}")
        } catch (e: UnsupportedOperationException) {
            Log.warn("open $url: ${e.message}")
        }
    }
}
