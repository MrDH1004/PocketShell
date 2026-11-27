package com.neststudio.pocketshell.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import com.neststudio.pocketshell.core.DiscoveredHost
import com.neststudio.pocketshell.core.HostAddress
import com.neststudio.pocketshell.core.PcPath
import com.neststudio.pocketshell.core.PinPairing
import com.neststudio.pocketshell.core.ProjectOptions
import com.neststudio.pocketshell.core.SessionInfo
import com.neststudio.pocketshell.data.Pairing
import com.neststudio.pocketshell.net.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.json.JSONException
import java.io.IOException

@Composable
private fun Dialog(title: String, size: DpSize, onClose: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    DialogWindow(onCloseRequest = onClose, state = rememberDialogState(size = size), title = title, resizable = false) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme) {
            Surface(Modifier.fillMaxSize(), color = Bg) { Column(Modifier.padding(20.dp)) { content() } }
        }
    }
}

/** PC 연결: PCs found on the LAN, or an address (Tailscale), + the PIN from the PC's 휴대폰 연결 window. */
@Composable
fun PairDialog(deviceName: String, onClose: () -> Unit, onPaired: (Pairing) -> Unit) {
    var found by remember { mutableStateOf<List<DiscoveredHost>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<DiscoveredHost?>(null) }
    var pin by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(deviceName) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var confirmFp by remember { mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null) }
    val scope = rememberCoroutineScope()

    fun search() {
        if (searching) return
        searching = true
        scope.launch {
            try {
                found = Discovery.discover()
                if (found.isEmpty()) status = "같은 망에서 찾은 PC 없음. 주소(Tailscale 등)를 직접 입력"
                else if (address.isBlank()) found.first().let { chosen = it; address = addressOf(it) }
            } catch (e: IOException) {
                status = e.message
            } finally {
                searching = false
            }
        }
    }
    LaunchedEffect(Unit) { search() }

    fun connect() {
        val addr = HostAddress.parse(address)
        val p = PinPairing.normalizePin(pin)
        when {
            addr == null -> { status = "주소 형식이 맞지 않음 (예: 192.168.0.5 또는 my-pc.tailnet.ts.net)"; return }
            !PinPairing.isValidPin(p) -> { status = "PIN은 숫자 6자리"; return }
        }
        busy = true
        status = "연결 중…"
        scope.launch {
            try {
                val disc = chosen?.takeIf { addressOf(it) == address.trim() }
                val pairing = PinPair.pair(addr!!, disc, p, name.trim().ifBlank { Prefs.defaultDeviceName() }) { short ->
                    val d = CompletableDeferred<Boolean>()
                    confirmFp = short to d
                    try { d.await() } finally { confirmFp = null }
                }
                Log.info("paired with ${pairing.pcName} (${pairing.hosts.joinToString()})")
                onPaired(pairing)
            } catch (e: ApiException) {
                status = when (e.code) {
                    401 -> "PIN이 틀렸거나 만료됨. PC에서 새 PIN을 받아 다시 입력"
                    429 -> "시도 횟수 초과. 잠시 후 다시"
                    else -> e.message
                }
            } catch (e: PairStopped) {
                status = e.message
            } catch (e: IOException) {
                status = "접속 실패: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    Dialog("PC 연결", DpSize(520.dp, 620.dp), onClose) {
        val fp = confirmFp
        if (fp != null) {
            Text("인증서 지문 확인", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            Text("PC의 '휴대폰 연결' 창에 나온 인증서 지문과 같은지 확인", color = Muted)
            Spacer(Modifier.height(20.dp))
            Text(fp.first, fontFamily = FontFamily.Monospace, fontSize = 26.sp, color = Gold, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            Spacer(Modifier.height(20.dp))
            Text("다르면 다른 기기가 응답한 것. 연결하지 말 것.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { fp.second.complete(false) }) { Text("다름 · 취소") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { fp.second.complete(true) }) { Text("같음 · 연결") }
            }
            return@Dialog
        }
        Text("PC 트레이 → 휴대폰 연결에 나오는 PIN 입력", color = Muted)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("같은 망의 PC", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            TextButton(onClick = ::search, enabled = !searching) { Text(if (searching) "찾는 중…" else "다시 찾기") }
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
            items(found) { d ->
                val sel = chosen == d && address.trim() == addressOf(d)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp)).background(if (sel) PanelHi else Panel)
                        .clickable { chosen = d; address = addressOf(d) }.padding(10.dp),
                ) {
                    Column {
                        Text(d.name, fontWeight = FontWeight.Medium)
                        Text(d.hosts.joinToString(", ") + " · 지문 " + d.fpShort, color = Muted, fontSize = 12.sp)
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(address, { address = it }, label = { Text("PC 주소") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(pin, { pin = it.filter { c -> c.isDigit() || c == ' ' }.take(7) }, label = { Text("PIN (6자리)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(name, { name = it }, label = { Text("이 기기 이름 (PC에 표시)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        status?.let { Text(it, color = Muted, fontSize = 13.sp) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("닫기") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::connect, enabled = !busy) { Text("연결") }
        }
    }
}

private fun addressOf(d: DiscoveredHost): String {
    val h = d.hosts.first()
    val host = if (h.contains(':')) "[$h]" else h
    return if (d.port == PinPairing.DEFAULT_PORT) h else "$host:${d.port}"
}

/** 새 세션: a folder under one of the PC's project folders, with an AI CLI or a plain terminal. */
@Composable
fun NewSessionDialog(pc: Pairing, onClose: () -> Unit, onCreated: (SessionInfo) -> Unit) {
    val api = remember(pc) { Apis.of(pc) }
    var options by remember { mutableStateOf<ProjectOptions?>(null) }
    var root by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>("불러오는 중…") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(pc.id) {
        try {
            val o = api.projectOptions()
            options = o
            root = o.roots.firstOrNull().orEmpty()
            agent = o.agents.firstOrNull()?.id
            status = null
        } catch (e: IOException) {
            status = "프로젝트 폴더를 불러올 수 없음: ${e.message}"
        } catch (e: JSONException) {
            status = "PC 응답을 읽을 수 없음: ${e.message}"
        }
    }

    fun create() {
        val name = folder.trim()
        if (root.isBlank()) { status = "위치를 고르세요"; return }
        if (name.any { it in "\\/:*?\"<>|" }) { status = "폴더 이름에 \\ / : * ? \" < > | 는 쓸 수 없음"; return }
        busy = true
        status = "만드는 중…"
        scope.launch {
            try {
                val a = agent
                val s = when {
                    name.isEmpty() -> api.createSession(null, null, 120, 32, cwd = root, agent = a)
                    a != null -> api.createProject(root, name, a, 120 to 32)
                    else -> api.createSession(null, name, 120, 32, cwd = PcPath.child(root, name))
                }
                onCreated(s)
            } catch (e: IOException) {
                status = e.message
            } catch (e: JSONException) {
                status = e.message
            } finally {
                busy = false
            }
        }
    }

    Dialog("새 세션 — ${pc.displayName}", DpSize(520.dp, 600.dp), onClose) {
        val o = options
        if (o != null) {
            Text("위치", fontWeight = FontWeight.Medium)
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 150.dp)) {
                items(o.roots) { r ->
                    Row(Modifier.fillMaxWidth().clickable { root = r }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(root == r, onClick = { root = r })
                        Text(r, fontSize = 14.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(folder, { folder = it }, label = { Text("폴더 이름 (비우면 위치 그대로, 없으면 만듦)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("실행", fontWeight = FontWeight.Medium)
            for (ag in o.agents) Row(Modifier.fillMaxWidth().clickable { agent = ag.id }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(agent == ag.id, onClick = { agent = ag.id }); Text(ag.name)
            }
            Row(Modifier.fillMaxWidth().clickable { agent = null }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(agent == null, onClick = { agent = null }); Text("터미널만")
            }
            Text("같은 폴더에 열린 AI 세션이 있으면 그 세션을 엶", color = Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        status?.let { Text(it, color = Muted, fontSize = 13.sp) }
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("닫기") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = ::create, enabled = !busy && o != null) { Text("시작") }
        }
    }
}

@Composable
fun SettingsDialog(prefs: Prefs, update: DesktopRelease?, onClose: () -> Unit, onUpdate: (DesktopRelease?) -> Unit, onChange: (Prefs) -> Unit) {
    var p by remember { mutableStateOf(prefs) }
    var checking by remember { mutableStateOf(false) }
    var updateText by remember { mutableStateOf(update?.let { "새 버전 v${it.version}" }) }
    val scope = rememberCoroutineScope()
    fun set(n: Prefs) { p = n; onChange(n) }

    Dialog("설정", DpSize(480.dp, 520.dp), onClose) {
        Text("보기", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("터미널 폰트 크기 (D2Coding)", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { set(p.copy(fontSize = (p.fontSize - 1).coerceAtLeast(Prefs.MIN_FONT))) }) { Text("−") }
            Text("${p.fontSize}", modifier = Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = { set(p.copy(fontSize = (p.fontSize + 1).coerceAtMost(Prefs.MAX_FONT))) }) { Text("+") }
        }
        Text("새로 여는 터미널 창부터 적용", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Text("이 기기", color = Muted, fontSize = 12.sp)
        OutlinedTextField(p.deviceName, { set(p.copy(deviceName = it)) }, label = { Text("이름 (다음 PC 연결부터 PC에 표시)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(16.dp))
        Text("업데이트", color = Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("PocketShell v${Updater.current}", modifier = Modifier.weight(1f))
            TextButton(enabled = !checking, onClick = {
                checking = true
                scope.launch {
                    try {
                        val r = Updater.check()
                        onUpdate(r)
                        updateText = r?.let { "새 버전 v${it.version}" } ?: "최신 버전"
                    } catch (e: IOException) {
                        updateText = "확인 실패: ${e.message}"
                    } finally {
                        checking = false
                    }
                }
            }) { Text(if (checking) "확인 중…" else "업데이트 확인") }
        }
        updateText?.let { t ->
            val r = update
            Text(t, color = if (r != null) Gold else Muted, fontSize = 13.sp, modifier = if (r != null) Modifier.clickable { Browser.open(r.url) } else Modifier)
        }
        Spacer(Modifier.height(16.dp))
        Text("데이터 폴더: ${AppDirs.dir}", color = Muted, fontSize = 12.sp, modifier = Modifier.clickable { Browser.open(AppDirs.dir.toURI().toString()) })
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Gold)) { Text("닫기") }
        }
    }
}
