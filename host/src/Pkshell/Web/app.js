/* PocketShell browser client: PIN pairing, session list, terminal (xterm.js over /ws/sessions/{id}). */
(function () {
  "use strict";

  var TOKEN_KEY = "pocketshell.token";
  var NAME_KEY = "pocketshell.deviceName";
  var REPLAY_KB = 2048;
  var HISTORY_LINES = 3000;

  var $ = function (id) { return document.getElementById(id); };
  var enc = new TextEncoder();

  function storageGet(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function storageSet(k, v) { try { if (v == null) localStorage.removeItem(k); else localStorage.setItem(k, v); } catch (e) { /* private mode */ } }

  var token = storageGet(TOKEN_KEY);

  // ---------- views ----------
  function show(id) {
    ["pair", "list", "term"].forEach(function (v) { $(v).hidden = v !== id; });
  }

  // ---------- api ----------
  function ApiError(status, message) { this.status = status; this.message = message; }

  function api(method, path, body) {
    var opt = { method: method, headers: {} };
    if (token) opt.headers.Authorization = "Bearer " + token;
    if (body !== undefined) {
      opt.headers["Content-Type"] = "application/json";
      opt.body = JSON.stringify(body);
    }
    return fetch(path, opt).then(function (r) {
      if (r.status === 204) return null;
      return r.text().then(function (t) {
        var j = null;
        try { j = t ? JSON.parse(t) : null; } catch (e) { /* not json */ }
        if (!r.ok) {
          if (r.status === 401 && path !== "/api/pair/claim") return unpaired();
          throw new ApiError(r.status, (j && j.error) || ("HTTP " + r.status));
        }
        return j;
      });
    });
  }

  function unpaired() {
    token = null;
    storageSet(TOKEN_KEY, null);
    closeTerminal();
    stopPolling();
    showPair("연결이 해제됐어. PIN으로 다시 연결.");
    throw new ApiError(401, "unauthorized");
  }

  // ---------- pairing ----------
  function defaultDeviceName() {
    var ua = navigator.userAgent;
    var os = /iPhone/.test(ua) ? "iPhone" : /iPad/.test(ua) ? "iPad" : /Macintosh/.test(ua) ? "Mac" : /Windows/.test(ua) ? "Windows" : /Android/.test(ua) ? "Android" : /Linux/.test(ua) ? "Linux" : "브라우저";
    var br = /Edg\//.test(ua) ? "Edge" : /Chrome\//.test(ua) ? "Chrome" : /Firefox\//.test(ua) ? "Firefox" : /Safari\//.test(ua) ? "Safari" : "";
    return br ? os + " · " + br : os;
  }

  function showPair(message) {
    show("pair");
    $("pair-error").textContent = message || "";
    $("device-name").value = storageGet(NAME_KEY) || defaultDeviceName();
    $("pin").value = "";
    $("pin").focus();
  }

  $("pair-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var pin = $("pin").value.trim();
    var name = $("device-name").value.trim() || defaultDeviceName();
    if (!/^[0-9]{6}$/.test(pin)) { $("pair-error").textContent = "PIN은 숫자 6자리."; $("pin").focus(); return; }
    $("pair-submit").disabled = true;
    $("pair-error").textContent = "";
    api("POST", "/api/pair/claim", { pin: pin, deviceName: name }).then(function (r) {
      token = r.token;
      storageSet(TOKEN_KEY, token);
      storageSet(NAME_KEY, name);
      openList();
    }).catch(function (err) {
      $("pair-error").textContent = err.status === 401 ? "PIN이 틀렸거나 만료됨. 트레이에서 새 PIN 확인."
        : err.status === 429 ? "실패가 너무 많음. 잠시 후 다시 시도." : "연결 실패: " + (err.message || err);
      $("pin").select();
    }).then(function () { $("pair-submit").disabled = false; });
  });

  // ---------- session list ----------
  var pollTimer = null;
  var info = null;

  function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

  function openList() {
    closeTerminal();
    show("list");
    if (location.hash) history.replaceState(null, "", location.pathname);
    document.title = "PocketShell";
    loadList();
    stopPolling();
    pollTimer = setInterval(function () { if (!document.hidden) loadList(true); }, 5000);
  }

  function projectName(cwd) {
    var t = (cwd || "").trim().replace(/[\\/]+$/, "");
    var n = t.split(/[\\/]/).pop();
    return n || null;
  }
  function displayTitle(s) { return projectName(s.cwd) || s.title || s.shell || s.id; }
  function isHistory(s) { return s.kind === "history" || s.kind === "recent" || (s.kind === "host" && s.state === "exited"); }
  function isRunningHost(s) { return s.kind === "host" && s.state === "running"; }
  function isPlainCmd(s) { return (s.kind === "external" || s.kind === "recent") && /\\system32\\cmd\.exe$/i.test((s.title || "").trim()); }
  // A console sitting in C:\Windows\System32 (an administrator terminal): no project there. Same rule as the phone app.
  function isSystem32Console(s) {
    return !isRunningHost(s) && !(s.kind === "external" && s.agent) && /^[A-Za-z]:\\windows\\system32$/i.test((s.cwd || "").trim().replace(/[\\/]+$/, ""));
  }
  function dirKey(cwd) { return (cwd || "").trim().replace(/[\\/]+$/, "").toLowerCase(); }

  var STATE_LABEL = { working: "작업 중", waiting: "입력 대기", done: "끝남" };

  function timeAgo(iso) {
    var t = Date.parse(iso);
    if (!t) return "";
    var s = Math.max(0, (Date.now() - t) / 1000);
    if (s < 60) return "방금";
    if (s < 3600) return Math.floor(s / 60) + "분 전";
    if (s < 86400) return Math.floor(s / 3600) + "시간 전";
    return Math.floor(s / 86400) + "일 전";
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function button(label, cls, onClick, aria) {
    var b = el("button", cls, label);
    b.type = "button";
    if (aria) b.setAttribute("aria-label", aria);
    b.addEventListener("click", onClick);
    return b;
  }

  function loadList(quiet) {
    var pInfo = info ? Promise.resolve(info) : api("GET", "/api/info").then(function (i) { info = i; return i; });
    return Promise.all([pInfo, api("GET", "/api/sessions")]).then(function (res) {
      var i = res[0];
      $("pc-name").textContent = i.name || "PC";
      $("pc-meta").textContent = "PocketShell " + (i.version || "") + (i.share ? " · 공유받은 세션" : "");
      $("new-session").hidden = !!i.share;
      $("list-error").hidden = true;
      renderSessions(res[1].sessions || []);
    }).catch(function (err) {
      if (err.status === 401) return;
      $("list-error").textContent = "PC 응답 없음: " + (err.message || err);
      $("list-error").hidden = false;
      if (!quiet) renderSessions([]);
    });
  }

  var lastRendered = null;

  function renderSessions(all) {
    // Redraw only when something changed: rebuilding the cards every 5 s could swallow a click in progress.
    var key = JSON.stringify(all) + "|" + (info && info.share ? 1 : 0) + "|" + Math.floor(Date.now() / 60000);
    if (key === lastRendered) return;
    lastRendered = key;
    var list = all.filter(function (s) { return !isPlainCmd(s) && !isSystem32Console(s); });
    var running = list.filter(isRunningHost).sort(function (a, b) { return (b.lastActivity || "").localeCompare(a.lastActivity || ""); });
    var external = list.filter(function (s) { return s.kind === "external"; }).sort(function (a, b) { return (a.pid || 1e9) - (b.pid || 1e9); });
    var seen = {};
    var recent = list.filter(isHistory).sort(function (a, b) { return (b.lastActivity || "").localeCompare(a.lastActivity || ""); })
      .filter(function (s) { var k = dirKey(s.cwd) || ("id:" + s.id); if (seen[k]) return false; seen[k] = true; return true; });

    var box = $("sections");
    var focusedId = document.activeElement && document.activeElement.closest && document.activeElement.closest("[data-id]");
    focusedId = focusedId ? focusedId.getAttribute("data-id") + "|" + (document.activeElement.getAttribute("data-act") || "") : null;
    box.textContent = "";
    if (!running.length && !external.length && !recent.length) {
      box.appendChild(el("p", "empty", "세션 없음. '새 세션'으로 시작."));
      return;
    }
    section(box, "실행 중", running);
    section(box, "PC에서 연 콘솔", external);
    section(box, "최근", recent);
    if (focusedId) {
      var parts = focusedId.split("|");
      var again = box.querySelector('[data-id="' + CSS.escape(parts[0]) + '"] [data-act="' + parts[1] + '"]');
      if (again) again.focus();
    }
  }

  function section(box, title, items) {
    if (!items.length) return;
    box.appendChild(el("h2", null, title + " " + items.length));
    var ul = el("ul", "sessions");
    items.forEach(function (s) { ul.appendChild(card(s)); });
    box.appendChild(ul);
  }

  function card(s) {
    var li = el("li", "session");
    li.setAttribute("data-id", s.id);
    var open = el("button", "open");
    open.type = "button";
    open.setAttribute("data-act", "open");
    var name = el("span", "name", displayTitle(s));
    open.appendChild(name);
    var subText = [s.cwd, isHistory(s) ? timeAgo(s.lastActivity) : null].filter(Boolean).join(" · ");
    open.appendChild(el("span", "sub", subText || s.shell || ""));
    open.addEventListener("click", function () { openSession(s); });
    li.appendChild(open);
    if (s.agentState && STATE_LABEL[s.agentState] && !isHistory(s)) {
      li.appendChild(el("span", "badge " + s.agentState, STATE_LABEL[s.agentState]));
    }
    var actions = el("div", "actions");
    if (info && info.share) { li.appendChild(actions); return li; }
    if (isHistory(s)) {
      actions.appendChild(withAct(button("다시 열기", null, function () { openSession(s); }), "resume"));
      actions.appendChild(withAct(button("삭제", "danger", function () { removeSession(s); }, "삭제: " + displayTitle(s)), "delete"));
    } else {
      actions.appendChild(withAct(button("종료", "danger", function () { endSession(s); }, "세션 종료: " + displayTitle(s)), "end"));
    }
    li.appendChild(actions);
    return li;
  }
  function withAct(b, act) { b.setAttribute("data-act", act); return b; }

  function endSession(s) {
    var what = s.kind === "external" ? "PC 콘솔 창과 그 안에서 실행 중인 프로그램이 모두 종료됨." : "기록은 '최근'에 남음.";
    if (!confirm("'" + displayTitle(s) + "' 세션을 종료할까?\n" + what)) return;
    api("POST", "/api/sessions/" + encodeURIComponent(s.id) + "/end").then(function () { loadList(); })
      .catch(function (err) { if (err.status !== 401) alert("종료 실패: " + err.message); });
  }

  function removeSession(s) {
    if (!confirm("'" + displayTitle(s) + "' 기록을 목록에서 삭제할까? 폴더와 파일은 그대로 남음.")) return;
    api("DELETE", "/api/sessions/" + encodeURIComponent(s.id)).then(function () { loadList(); })
      .catch(function (err) { if (err.status !== 401) alert("삭제 실패: " + err.message); });
  }

  function openSession(s) {
    if (isHistory(s)) {
      api("POST", "/api/sessions/" + encodeURIComponent(s.id) + "/resume").then(function (n) { openTerminal(n); })
        .catch(function (err) { if (err.status !== 401) alert("다시 열기 실패: " + err.message); });
    } else {
      openTerminal(s);
    }
  }

  $("refresh").addEventListener("click", function () { info = null; loadList(); });
  $("unpair").addEventListener("click", function () {
    if (!confirm("이 브라우저의 연결을 해제할까? 다시 쓰려면 PIN이 필요함.\nPC 쪽 기기 목록에서는 트레이에서 지울 수 있음.")) return;
    token = null;
    storageSet(TOKEN_KEY, null);
    stopPolling();
    info = null;
    showPair("");
  });

  // ---------- new session ----------
  $("new-session").addEventListener("click", function () {
    var dlg = $("new-dialog");
    $("new-error").textContent = "";
    api("GET", "/api/projects").then(function (p) {
      var dl = $("new-roots");
      dl.textContent = "";
      (p.roots || []).forEach(function (r) { var o = document.createElement("option"); o.value = r; dl.appendChild(o); });
      if (!$("new-cwd").value && p.roots && p.roots.length) $("new-cwd").value = p.roots[0];
      var sel = $("new-agent");
      sel.length = 1;
      (p.agents || []).forEach(function (a) { var o = document.createElement("option"); o.value = a.id; o.textContent = a.name; sel.appendChild(o); });
    }).catch(function () { /* the folder can still be typed */ });
    dlg.showModal();
    $("new-cwd").focus();
  });
  $("new-cancel").addEventListener("click", function () { $("new-dialog").close(); });
  $("new-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var body = { cwd: $("new-cwd").value.trim() || null, agent: $("new-agent").value || null };
    $("new-ok").disabled = true;
    api("POST", "/api/sessions", body).then(function (s) {
      $("new-dialog").close();
      openTerminal(s);
    }).catch(function (err) {
      $("new-error").textContent = err.message || String(err);
    }).then(function () { $("new-ok").disabled = false; });
  });

  // ---------- terminal ----------
  var term = null, fit = null, ws = null, current = null;
  var retry = 0, retryTimer = null, closedByUs = false, exited = false;
  var resizeTimer = null, lastSent = "";
  var ctrlArmed = false;

  function status(text, bad) {
    var s = $("term-status");
    s.textContent = text;
    s.classList.toggle("bad", !!bad);
  }

  function ensureTerm() {
    if (term) return;
    term = new window.Terminal({
      cursorBlink: true,
      scrollback: 10000,
      fontSize: 14,
      fontFamily: 'Menlo, "SF Mono", "D2Coding", Consolas, "Apple SD Gothic Neo", "Malgun Gothic", monospace',
      allowProposedApi: true,
      macOptionIsMeta: false,
      // Windows Terminal (Campbell) colors. black must equal the background: PC consoles mark cells with an explicit
      // background 0, and xterm.js's own black (#2e3436) drew those as gray bars behind the text.
      theme: {
        background: "#0c0c0c", foreground: "#cccccc", cursor: "#ffffff", selectionBackground: "#3a3d41",
        black: "#0c0c0c", red: "#c50f1f", green: "#13a10e", yellow: "#c19c00", blue: "#0037da", magenta: "#881798", cyan: "#3a96dd", white: "#cccccc",
        brightBlack: "#767676", brightRed: "#e74856", brightGreen: "#16c60c", brightYellow: "#f9f1a5", brightBlue: "#3b78ff", brightMagenta: "#b4009e", brightCyan: "#61d6d6", brightWhite: "#f2f2f2",
      },
    });
    fit = new window.FitAddon.FitAddon();
    term.loadAddon(fit);
    if (window.WebLinksAddon) term.loadAddon(new window.WebLinksAddon.WebLinksAddon());
    term.open($("term-box"));
    term.onData(function (d) { sendInput(applyCtrl(d)); });
    term.onBinary(function (d) {
      var b = new Uint8Array(d.length);
      for (var i = 0; i < d.length; i++) b[i] = d.charCodeAt(i) & 0xff;
      send(b);
    });
    window.addEventListener("resize", scheduleFit);
    if (window.visualViewport) window.visualViewport.addEventListener("resize", scheduleFit);
  }

  function applyCtrl(d) {
    if (!ctrlArmed || d.length !== 1) return d;
    setCtrl(false);
    var c = d.toUpperCase().charCodeAt(0);
    if (c >= 64 && c <= 95) return String.fromCharCode(c - 64);
    if (d === " ") return "\x00";
    return d;
  }
  function setCtrl(on) { ctrlArmed = on; $("ctrl-key").setAttribute("aria-pressed", on ? "true" : "false"); }

  function sendInput(text) { send(enc.encode(text)); }
  function send(bytes) { if (ws && ws.readyState === 1 && !exited) ws.send(bytes); }

  function scheduleFit() {
    if (!term || $("term").hidden) return;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(doFit, 80);
  }
  function doFit() {
    if (!term) return;
    try { fit.fit(); } catch (e) { return; }
    sendSize();
  }
  function sendSize() {
    if (!ws || ws.readyState !== 1 || !current || current.kind === "external") return;
    var key = term.cols + "x" + term.rows;
    if (key === lastSent) return;
    lastSent = key;
    ws.send(JSON.stringify({ type: "resize", cols: term.cols, rows: term.rows }));
  }

  function openTerminal(s) {
    stopPolling();
    current = s;
    show("term");
    $("term-title").textContent = displayTitle(s);
    $("term-cwd").textContent = s.cwd || s.shell || "";
    document.title = displayTitle(s) + " — PocketShell";
    history.replaceState(null, "", "#" + encodeURIComponent(s.id));
    ensureTerm();
    term.reset();
    try { fit.fit(); } catch (e) { /* hidden */ }
    retry = 0;
    exited = false;
    connect();
    term.focus();
  }

  function wsUrl() {
    var q = "client=web&token=" + encodeURIComponent(token) + "&replayKb=" + REPLAY_KB + "&historyLines=" + HISTORY_LINES;
    if (current.kind !== "external") q += "&cols=" + term.cols + "&rows=" + term.rows;
    q += "&openPcTab=0";
    return "wss://" + location.host + "/ws/sessions/" + encodeURIComponent(current.id) + "?" + q;
  }

  function connect() {
    closeSocket();
    closedByUs = false;
    lastSent = "";
    status(retry ? "다시 연결 중…" : "연결 중…");
    var sock = new WebSocket(wsUrl());
    sock.binaryType = "arraybuffer";
    ws = sock;
    sock.onopen = function () {
      if (ws !== sock) return;
      retry = 0;
      term.reset();
      status("연결됨");
      lastSent = term.cols + "x" + term.rows;
    };
    sock.onmessage = function (ev) {
      if (ws !== sock) return;
      if (typeof ev.data !== "string") { term.write(new Uint8Array(ev.data)); return; }
      var m;
      try { m = JSON.parse(ev.data); } catch (e) { return; }
      switch (m.type) {
        case "hello":
          if (m.session) {
            current = Object.assign({}, current, m.session);
            $("term-title").textContent = displayTitle(current);
            $("term-cwd").textContent = current.cwd || current.shell || "";
          }
          break;
        case "history":
          (m.lines || []).forEach(function (l) { term.write(l + "\r\n"); });
          break;
        case "size":
          // The PC session's size (several windows share it; it follows the one typed in last): draw at that size
          // so lines wrap as they do there. This window's own size goes up in doFit.
          if (m.cols > 0 && m.rows > 0 && (m.cols !== term.cols || m.rows !== term.rows)) term.resize(m.cols, m.rows);
          break;
        case "replay-end":
          term.scrollToBottom();
          break;
        case "exit":
          exited = true;
          status("세션 끝남 (코드 " + m.code + ")", true);
          term.write("\r\n\x1b[2m[세션이 끝났어. ← 목록에서 다시 열 수 있음]\x1b[0m\r\n");
          break;
      }
    };
    sock.onclose = function (ev) {
      if (ws !== sock) return;
      ws = null;
      if (closedByUs || exited) return;
      if (ev.code === 4401) { unpairedSafe(); return; }
      if (ev.code === 1008 || ev.code === 4403) { status("접근 거부: " + (ev.reason || ev.code), true); return; }
      scheduleReconnect(ev.reason || ("code " + ev.code));
    };
  }

  function unpairedSafe() { try { unpaired(); } catch (e) { /* thrown on purpose */ } }

  function scheduleReconnect(reason) {
    retry++;
    var delay = Math.min(15000, 500 * Math.pow(2, Math.min(retry, 5)));
    status("끊김 (" + reason + ") · " + Math.round(delay / 1000) + "초 후 재연결", true);
    clearTimeout(retryTimer);
    retryTimer = setTimeout(function () {
      if (!current || $("term").hidden) return;
      // The session may have ended or the token been revoked while away: ask before reconnecting.
      api("GET", "/api/sessions/" + encodeURIComponent(current.id)).then(function () { connect(); }).catch(function (err) {
        if (err.status === 401) return;
        if (err.status === 404) { status("세션이 없어짐", true); return; }
        scheduleReconnect(err.message || "PC 응답 없음");
      });
    }, delay);
  }

  function closeSocket() {
    clearTimeout(retryTimer);
    if (ws) {
      closedByUs = true;
      var s = ws;
      ws = null;
      try { s.close(1000); } catch (e) { /* already closed */ }
    }
  }

  function closeTerminal() {
    closeSocket();
    current = null;
    setCtrl(false);
  }

  $("back").addEventListener("click", function () { openList(); });

  document.addEventListener("visibilitychange", function () {
    // Back from sleep / another tab: reconnect at once instead of waiting out the backoff.
    if (!document.hidden && current && !ws && !exited && !$("term").hidden) { retry = 0; connect(); }
  });

  // On-screen keys for touch screens (iPhone / iPad Safari have no Esc, Tab, Ctrl or arrows).
  var KEYS = { esc: "\x1b", tab: "\t", ctrlc: "\x03", enter: "\r" };
  var ARROWS = { up: "A", down: "B", right: "C", left: "D" };
  if (window.matchMedia && window.matchMedia("(pointer: coarse)").matches) $("keybar").hidden = false;
  $("keybar").addEventListener("click", function (e) {
    var b = e.target.closest("button");
    if (!b || !term) return;
    var k = b.getAttribute("data-key");
    if (k === "ctrl") { setCtrl(!ctrlArmed); term.focus(); return; }
    var seq = KEYS[k];
    if (ARROWS[k]) seq = (term.modes && term.modes.applicationCursorKeysMode ? "\x1bO" : "\x1b[") + ARROWS[k];
    if (seq) sendInput(seq);
    term.focus();
  });

  // ---------- start ----------
  function start() {
    if (!token) { showPair(""); return; }
    var id = decodeURIComponent((location.hash || "").slice(1));
    if (id) {
      api("GET", "/api/sessions/" + encodeURIComponent(id)).then(function (s) {
        if (s && s.state === "running" || s && s.kind === "external") openTerminal(s); else openList();
      }).catch(function (err) { if (err.status !== 401) openList(); });
    } else {
      openList();
    }
  }
  start();
})();
