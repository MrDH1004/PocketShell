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

  // The host build this page came from (filled in by the host when serving app.js). An installed web app is resumed,
  // not reloaded, so after a host update it would keep running the old page: compare and reload once.
  var PAGE_VERSION = "__PKSHELL_VERSION__";
  function checkVersion(hostVersion) {
    if (!hostVersion || PAGE_VERSION.indexOf("__") === 0 || hostVersion === PAGE_VERSION) return;
    var key = "pocketshell.reloadedFor", done = null;
    try { done = sessionStorage.getItem(key); } catch (e) { /* private mode */ }
    if (done === hostVersion) return; // already tried for this version: do not loop
    try { sessionStorage.setItem(key, hostVersion); } catch (e) { /* private mode */ }
    location.reload();
  }
  function checkHostVersion() {
    if (!token) return;
    api("GET", "/api/info").then(function (i) { if (i) { info = info ? Object.assign(info, { version: i.version, webUrl: i.webUrl }) : i; checkVersion(i.version); } })
      .catch(function () { /* offline: the terminal shows it */ });
  }

  // Android Chrome: the on-screen keyboard shrinks the page, so the key bar stays above it (Safari does not know the key).
  if (/Android/.test(navigator.userAgent)) {
    var vp = document.querySelector('meta[name="viewport"]');
    if (vp) vp.setAttribute("content", vp.getAttribute("content") + ", interactive-widget=resizes-content");
  }

  // ---------- views ----------
  function show(id) {
    ["pair", "list", "term", "files", "web"].forEach(function (v) { $(v).hidden = v !== id; });
    document.documentElement.classList.toggle("web-open", id === "web");
    // The terminal has its own scrollbar (past output); the page itself never scrolls behind it.
    document.documentElement.classList.toggle("term-open", id === "term");
    var t = $("term");
    var vv = window.visualViewport;
    t.style.height = id === "term" && vv ? Math.round(vv.height) + "px" : "";
    t.style.transform = "";
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
      checkVersion(i.version);
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
    if (s.webPorts && s.webPorts.length && !isHistory(s)) {
      var globe = withAct(button("", "icon ghost globe", function () {
        stopPolling();
        openWeb(s.webPorts[0], openList, s.webPorts);
      }, "웹 보기: localhost:" + s.webPorts.join(", ")), "web");
      globe.innerHTML = GLOBE_SVG;
      globe.title = "웹 보기 (localhost:" + s.webPorts.join(", ") + ")";
      li.appendChild(globe);
    }
    if (isHistory(s)) {
      actions.appendChild(withAct(button("다시 열기", null, function () { openSession(s); }), "resume"));
      actions.appendChild(withAct(button("삭제", "danger", function () { removeSession(s); }, "삭제: " + displayTitle(s)), "delete"));
    } else {
      actions.appendChild(withAct(button("종료", "danger", function () { endSession(s); }, "세션 종료: " + displayTitle(s)), "end"));
    }
    li.appendChild(actions);
    // Phones: one compact row like the app; the actions are behind ⋯ (or a long press on the row).
    var menu = function () {
      var items = isHistory(s)
        ? [["다시 열기", function () { openSession(s); }], ["삭제", function () { removeSession(s); }, "danger"]]
        : [["열기", function () { openSession(s); }], ["세션 종료", function () { endSession(s); }, "danger"]];
      if (window.PsFiles && window.PsFiles.sheet) window.PsFiles.sheet(displayTitle(s), items);
    };
    li.appendChild(withAct(button("⋯", "ghost more", menu, "메뉴: " + displayTitle(s)), "more"));
    open.addEventListener("contextmenu", function (e) { e.preventDefault(); menu(); });
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
  var resizeTimer = null, lastSent = "", serverSize = null;
  var BASE_FONT = 14, MIN_FONT = 7;
  var TERM_FONT_FALLBACK = 'Menlo, "SF Mono", "D2Coding", Consolas, "Apple SD Gothic Neo", "Malgun Gothic", monospace';
  var TERM_FONT = '"D2Coding", ' + TERM_FONT_FALLBACK;
  /**
   * Font size for this screen: phones 13px, tablets / small windows 14px, laptops 15px, large monitors 16px.
   * Re-read on every fit, so rotating the phone or resizing the window adjusts it.
   */
  var FONT_KEY = "pocketshell.fontSize";
  function autoFont() {
    var chosen = parseFloat(storageGet(FONT_KEY));
    if (chosen >= 10 && chosen <= 24) return chosen;
    var w = window.innerWidth || 1024;
    return w < 480 ? 13 : w < 1024 ? 14 : w < 1600 ? 15 : 16;
  }
  var ctrlArmed = false, altArmed = false;

  var connected = false;
  /**
   * Connection line. Wide screens: the right of the bar. Narrow ones (phones) have no room there, so the subtitle
   * (folder) shows it instead whenever it is not plain "연결됨", and a lost connection also gets a banner with the
   * likely reason — a frozen screen was the only sign before.
   */
  function status(text, bad) {
    var s = $("term-status");
    s.textContent = text;
    s.classList.toggle("bad", !!bad);
    connected = text === "연결됨" || text.indexOf("기록 ") === 0;
    var sub = $("term-cwd");
    var plain = connected;
    sub.textContent = plain ? (current && (current.cwd || current.shell)) || "" : text;
    sub.classList.toggle("bad", !plain && !!bad);
    sub.classList.toggle("busy", !plain && !bad);
    var banner = $("conn-banner");
    if (bad && /끊김/.test(text)) {
      banner.textContent = "PC에 연결 안 됨. " + reachHint() + " (" + text.replace(/^끊김 /, "") + ")";
      banner.hidden = false;
    } else banner.hidden = true;
  }

  /** What to check when the PC cannot be reached from this page. */
  function reachHint() {
    if (/\.ts\.net$/.test(location.hostname)) return "이 주소는 Tailscale 주소: 이 기기에서 Tailscale이 켜져 있는지 확인.";
    if (/^100\./.test(location.hostname)) return "Tailscale 주소: 이 기기에서 Tailscale이 켜져 있는지 확인.";
    return "PC가 켜져 있고 같은 Wi-Fi인지 확인.";
  }

  function ensureTerm() {
    if (term) return;
    term = new window.Terminal({
      cursorBlink: true,
      scrollback: 10000,
      fontSize: BASE_FONT,
      fontFamily: TERM_FONT_FALLBACK,
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
    // Hangul in other monospace fonts is narrower than its two cells, so Korean showed with gaps between letters.
    // D2Coding (shipped with the page) is drawn exactly two cells wide; switch once it is loaded.
    if (document.fonts && document.fonts.load) {
      document.fonts.load(BASE_FONT + 'px "D2Coding"', "가A").then(function () {
        term.options.fontFamily = TERM_FONT;
        scheduleFit();
      }).catch(function () { /* keep the fallback */ });
    }
    // '↓ 실시간' follows any scroll (finger, wheel, scrollbar); the buffer position settles on the next frame.
    var liveCheck = function () { requestAnimationFrame(updateToLive); };
    term.onScroll(liveCheck);
    var vpEl = term.element && term.element.querySelector(".xterm-viewport");
    if (vpEl) vpEl.addEventListener("scroll", liveCheck, { passive: true });
    term.onData(function (d) {
      if (quietFocus && (d === "\x1b[I" || d === "\x1b[O")) return; // our own refocus, not the user's
      if (imeDrop !== null && d === imeDrop) { imeDrop = null; return; } // a re-opened word: sent by imeFix instead
      sendInput(applyAlt(applyCtrl(d)));
    });
    if (touchScreen()) mobileIme(term.textarea); else imeFix(term.textarea);
    term.onBinary(function (d) {
      var b = new Uint8Array(d.length);
      for (var i = 0; i < d.length; i++) b[i] = d.charCodeAt(i) & 0xff;
      send(b);
    });
    window.addEventListener("resize", scheduleFit);
    if (window.visualViewport) window.visualViewport.addEventListener("resize", scheduleFit);
  }

  // ---------- phone keyboards: mirror the input line (as the phone app's TerminalView) ----------
  // On touch screens xterm.js's own keyboard handling is bypassed. The hidden textarea holds the input line as the
  // keyboard believes it is; after every keyboard edit the PC is brought to the same text (Backspace per removed
  // character, then the new tail), so Korean shows while it is composed (ㅁ → 모 → 몸) and a word the keyboard
  // re-opens is not typed twice. The keyboard's Backspace inside a syllable (한 → 하 → ㅎ) deletes the whole character
  // instead, as on a PC. The mirror starts over at Enter and whenever keys are sent another way (key bar, phrases).
  /**
   * Gives the terminal's input back the keyboard. After a dialog closes, the textarea is focused again but the
   * browser does not insert typed text into it until it is blurred and focused once more. The focus-out/in reports
   * xterm.js would send for that (programs such as Claude Code turn them on) are dropped.
   */
  var quietFocus = false;
  function refocusTerm() {
    if (!term || !term.textarea || $("term").hidden) return;
    quietFocus = true;
    var ta = term.textarea, keep = ta.value; // the phone keyboard mirror's line: must survive the refocus
    ta.blur();
    ta.focus({ preventScroll: true });
    if (ta.value !== keep) { ta.value = keep; ta.setSelectionRange(keep.length, keep.length); }
    setTimeout(function () { quietFocus = false; }, 60);
  }

  function touchScreen() { return !!(window.matchMedia && window.matchMedia("(pointer: coarse)").matches); }

  var HG_CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ";
  var HG_JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ";
  var HG_JONG = " ㄱㄲㄳㄴㄵㄶㄷㄹㄺㄻㄼㄽㄾㄿㅀㅁㅂㅄㅅㅆㅇㅈㅊㅋㅌㅍㅎ";
  var HG_COMPOUND = "ㅘㅗㅏㅙㅗㅐㅚㅗㅣㅝㅜㅓㅞㅜㅔㅟㅜㅣㅢㅡㅣ" + "ㄳㄱㅅㄵㄴㅈㄶㄴㅎㄺㄹㄱㄻㄹㅁㄼㄹㅂㄽㄹㅅㄾㄹㅌㄿㄹㅍㅀㄹㅎㅄㅂㅅ";
  function hgJamo(j) {
    for (var k = 0; k < HG_COMPOUND.length; k += 3) if (HG_COMPOUND[k] === j) return HG_COMPOUND[k + 1] + HG_COMPOUND[k + 2];
    return j;
  }
  /** Hangul as the jamo keys that build it ("닭" → ㄷㅏㄹㄱ); other characters unchanged. */
  function hgKeys(text) {
    var out = "";
    for (var i = 0; i < text.length; i++) {
      var c = text.charCodeAt(i);
      if (c >= 0xac00 && c <= 0xd7a3) {
        var x = c - 0xac00;
        out += HG_CHO[Math.floor(x / 588)] + hgJamo(HG_JUNG[Math.floor((x % 588) / 28)]);
        if (x % 28) out += hgJamo(HG_JONG[x % 28]);
      } else out += hgJamo(text[i]);
    }
    return out;
  }
  /** The keyboard changed Hangul `from` → `to` only by removing jamo (its Backspace inside a syllable). */
  function hgBackspace(from, to) {
    if (!from || to.length > from.length || !/[\uac00-\ud7a3\u3131-\u318e]/.test(from)) return false;
    var a = hgKeys(from), b = hgKeys(to);
    return b.length < a.length && a.indexOf(b) === 0;
  }
  function dropLast(t) { var c = Array.from(t); c.pop(); return c.join(""); }

  var mirrorReset = function () {};
  function mobileIme(ta) {
    if (!ta) return;
    var sent = "";       // what the PC has of the current input line
    var before = "";     // textarea value before the current keyboard edit
    ta.setAttribute("autocomplete", "off");
    ta.setAttribute("autocorrect", "off");
    ta.setAttribute("autocapitalize", "off");
    ta.setAttribute("spellcheck", "false");

    function syncTo(target) {
      var a = Array.from(sent), b = Array.from(target), k = 0;
      while (k < a.length && k < b.length && a[k] === b[k]) k++;
      var out = new Array(a.length - k + 1).join("\x7f") + b.slice(k).join("").replace(/\n/g, "\r");
      sent = target;
      if (out) { stopTouchFling(); if (rf && rf.scroll > 0) { rf.scroll = 0; drawReflow(); } send(enc.encode(applyAlt(applyCtrl(out)))); }
    }
    function reset() { sent = ""; before = ""; ta.value = ""; }
    mirrorReset = reset;

    var box = $("term-box");
    // Capture on the parent: these never reach xterm.js's own listeners on the textarea.
    ["compositionstart", "compositionupdate", "compositionend", "keypress"].forEach(function (t) {
      box.addEventListener(t, function (e) { if (e.target === ta) e.stopPropagation(); }, true);
    });
    box.addEventListener("beforeinput", function (e) {
      if (e.target !== ta) return;
      e.stopPropagation();
      before = ta.value;
      if (e.inputType === "insertLineBreak" || e.inputType === "insertParagraph") {
        e.preventDefault();
        syncTo(ta.value);
        send(enc.encode("\r"));
        reset();
      }
    }, true);
    box.addEventListener("input", function (e) {
      if (e.target !== ta) return;
      e.stopPropagation();
      if (e.inputType === "insertCompositionText" && hgBackspace(before, ta.value) && ta.value.length <= before.length) {
        // Backspace inside a syllable: delete the whole character, end the composition.
        var kept = dropLast(before);
        ta.value = kept;
        ta.setSelectionRange(kept.length, kept.length);
        syncTo(kept);
        refocusTerm(); // ends the keyboard's composition so it does not bring the old syllable back
        return;
      }
      syncTo(ta.value);
    }, true);
    box.addEventListener("keydown", function (e) {
      if (e.target !== ta) return;
      if (e.isComposing || e.keyCode === 229 || e.key === "Unidentified" || e.key === "Process") { e.stopPropagation(); return; }
      if (e.key === "Enter") {
        e.stopPropagation(); e.preventDefault();
        syncTo(ta.value);
        send(enc.encode("\r"));
        reset();
        return;
      }
      if (e.key === "Backspace") {
        e.stopPropagation();
        if (!ta.value) { e.preventDefault(); stopTouchFling(); send(enc.encode("\x7f")); }
        return; // with text: the textarea deletes, the input event syncs
      }
      if (e.key.length === 1 && !e.ctrlKey && !e.altKey && !e.metaKey) { e.stopPropagation(); return; } // comes as input
      // Other keys (arrows, Tab, Esc, Ctrl+…, hardware keyboard): xterm.js sends them; the line may change, start over.
      reset();
    }, true);
    ta.addEventListener("blur", function () { before = ta.value; });
  }

  // ---------- phone keyboards re-opening a committed word (Samsung Keyboard: 한/영 right after Korean) ----------
  // Switching language commits "한" (sent), then the keyboard re-opens that same word as a new composition and goes on
  // with "한a". xterm.js then (1) drew the whole re-opened word again next to the sent one ("한한as"), and (2) lost a
  // Backspace that deleted part of it (the PC kept "한"). Here: the composition box shows only the unsent part, and when
  // such a composition ends the page sends the difference itself (Backspaces for removed sent chars, then the rest).
  var imeDrop = null;
  function cps(t) { return Array.from(t); }
  function imeFix(ta) {
    if (!ta) return;
    var start = 0, lastEnd = 0, reopened = null, first = true;
    var view = function () { return term.element && term.element.querySelector(".composition-view"); };
    var fixView = function () {
      var v = view();
      if (!v || !v.classList.contains("active")) return;
      var t = ta.value.substring(start);
      if (v.textContent !== t) v.textContent = t;
    };
    ta.addEventListener("compositionstart", function (e) {
      start = ta.value.length;
      reopened = null;
      first = true;
      imeDrop = null;
      // Within a moment of the last commit: possibly the keyboard re-opening the word before the cursor.
      ta.__ps_soon = e.timeStamp - lastEnd < 400;
    });
    ta.addEventListener("compositionupdate", function (e) {
      if (first) {
        first = false;
        var d = e.data || "";
        if (ta.__ps_soon && d && ta.value.length >= d.length && ta.value.slice(ta.value.length - d.length) === d) reopened = d;
      }
      setTimeout(fixView, 0);
    });
    ta.addEventListener("input", function (e) { if (e.inputType === "insertCompositionText") fixView(); });
    ta.addEventListener("compositionend", function (e) {
      lastEnd = e.timeStamp;
      if (reopened == null) return;
      var P = cps(reopened), F = cps(e.data || ""), k = 0;
      while (k < P.length && k < F.length && P[k] === F[k]) k++;
      var out = new Array(P.length - k + 1).join("\x7f") + F.slice(k).join("");
      // xterm will send value.substring(start) after this event: drop that one, send the real difference.
      var xtermWill = ta.value.substring(start);
      imeDrop = xtermWill ? xtermWill : null;
      if (out) sendInput(out);
      reopened = null;
    });
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
  /** Alt (latched on the key bar): ESC before the next key, as terminals send Meta. */
  function applyAlt(d) {
    if (!altArmed) return d;
    setAlt(false);
    return "\x1b" + d;
  }
  function setAlt(on) { altArmed = on; $("alt-key").setAttribute("aria-pressed", on ? "true" : "false"); }

  function sendInput(text) {
    mirrorReset();
    stopTouchFling();
    if (rf && rf.scroll > 0) { rf.scroll = 0; drawReflow(); } // typing returns to the live view
    send(enc.encode(text));
  }

  // ---------- PC console re-flow (narrow screens), as the phone app ----------
  // A PC console wider than this window at a readable font is not shrunk: its frames go into ConsoleHistory (lines
  // that scrolled off are kept) and are laid out again at this width by Reflow; swipe / wheel scrolls back.
  var rf = null; // { h: ConsoleHistory, cols: layout columns, scroll: lines back from live }
  var REFLOW_MIN_FONT = 11;

  /** Decides (from serverSize) whether to re-flow; sets the font and grid. True when re-flowing. */
  function chooseReflow() {
    if (!serverSize || !window.PsReflow) { rf = null; return false; }
    BASE_FONT = autoFont();
    setFont(BASE_FONT);
    var p = fit.proposeDimensions();
    if (!p || serverSize.cols <= p.cols) {
      if (rf) { rf = null; term.options.scrollback = 10000; term.reset(); }
      return false;
    }
    var target = window.PsReflow.Reflow.fitCols(serverSize.cols, p.cols);
    var f = BASE_FONT;
    while (f > REFLOW_MIN_FONT && p && p.cols < target) { f -= 0.5; setFont(f); p = fit.proposeDimensions(); }
    if (term.element) term.element.style.transform = "";
    try { fit.fit(); } catch (e) { /* hidden */ }
    if (!rf) {
      rf = { h: new window.PsReflow.ConsoleHistory(), cols: 0, scroll: 0 };
      term.options.scrollback = 0; // the history is ours; every redraw places whole rows
      term.reset();
    }
    rf.h.pcCols = serverSize.cols;
    rf.cols = Math.min(target, term.cols);
    rf.scroll = Math.min(rf.scroll, rf.h.maxScroll(term.rows, rf.cols));
    drawReflow();
    return true;
  }

  function drawReflow() {
    if (!rf || !rf.h.hasFrame) return;
    term.write(rf.h.render(term.rows, rf.scroll, rf.cols));
    // Only while connected: a redraw must not hide that the connection is gone (the screen would just look frozen).
    if (!exited && ws && ws.readyState === 1) status(rf.scroll > 0 ? "기록 " + rf.scroll + "줄 위" : "연결됨");
    updateToLive();
  }

  function scrolledBack() {
    if (rf) return rf.scroll > 0;
    if (!term) return false;
    var b = term.buffer.active;
    return b.viewportY < b.baseY;
  }
  function updateToLive() { $("to-live").hidden = !scrolledBack(); }
  $("to-live").addEventListener("click", function () {
    stopTouchFling();
    if (rf) { rf.scroll = 0; drawReflow(); } else if (term) term.scrollToBottom();
    updateToLive();
  });

  function scrollReflow(linesUp) {
    if (!rf || !rf.h.hasFrame) return false;
    var next = Math.max(0, Math.min(rf.h.maxScroll(term.rows, rf.cols), rf.scroll + linesUp));
    if (next !== rf.scroll) { rf.scroll = next; drawReflow(); }
    return true;
  }

  function cellHeight() {
    var r = document.querySelector("#term-box .xterm-rows > div");
    return (r && r.getBoundingClientRect().height) || 16;
  }

  // Touch scrolling, for every session: the text follows the finger (1 px = 1 px) and keeps going after a flick.
  // xterm.js's own touch scrolling on phones moved about a line per swipe. A re-flowed PC console scrolls its history.
  var stopTouchFling = function () {};

  // Picture zoom of the terminal (pinch): scale + offset of #term-box inside #term-wrap, top-left origin.
  var ZOOM_MAX = 4;
  var zoom = { s: 1, x: 0, y: 0 };
  function setZoom(sc, x, y) {
    var w = $("term-wrap"), b = $("term-box");
    var W = w.clientWidth, H = w.clientHeight;
    sc = Math.min(ZOOM_MAX, Math.max(1, sc));
    x = Math.min(0, Math.max(W - W * sc, x));
    y = Math.min(0, Math.max(H - H * sc, y));
    zoom = { s: sc, x: x, y: y };
    b.style.transform = sc === 1 ? "" : "translate(" + x + "px, " + y + "px) scale(" + sc + ")";
  }

  function scrollLinesBy(fingerDownLines) {
    if (rf) return scrollReflow(fingerDownLines);
    if (term) term.scrollLines(-fingerDownLines);
    return true;
  }

  (function () {
    var box = $("term-box"), wheelAcc = 0;
    var lastY = null, startY = 0, moved = false, acc = 0, v = 0, lastT = 0, fling = 0;
    var TAP_SLOP = 8, MIN_FLING = 0.35, FRICTION = 0.95;

    function applyDy(dy) {
      var ch = cellHeight();
      acc += dy;
      var n = Math.trunc(acc / ch);
      if (n) { acc -= n * ch; scrollLinesBy(n); }
    }
    function stopFling() { if (fling) { cancelAnimationFrame(fling); fling = 0; } }
    stopTouchFling = stopFling;

    box.addEventListener("wheel", function (e) {
      stopFling();
      if (!rf) return;
      e.preventDefault();
      e.stopPropagation();
      wheelAcc += e.deltaMode === 1 ? e.deltaY * cellHeight() : e.deltaY;
      var n = Math.trunc(wheelAcc / cellHeight());
      if (n) { wheelAcc -= n * cellHeight(); scrollReflow(-n); }
    }, { capture: true, passive: false });

    // Pinch zoom like a picture (the phone app's ZoomPanLayout): the terminal is scaled as it is, nothing re-wraps.
    // Two fingers zoom and move; one finger pans while zoomed in, and scrolls the text once the pan reaches an edge.
    var pinch = null, lastX = 0;
    function pt(t) { var r = $("term-wrap").getBoundingClientRect(); return { x: t.clientX - r.left, y: t.clientY - r.top }; }
    function dist(a, b) { return Math.hypot(a.x - b.x, a.y - b.y); }

    box.addEventListener("touchstart", function (e) {
      stopFling();
      if (e.touches.length === 2) {
        var a = pt(e.touches[0]), b = pt(e.touches[1]);
        pinch = { d0: Math.max(10, dist(a, b)), s0: zoom.s, m0: { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }, t0: { x: zoom.x, y: zoom.y } };
        lastY = null;
        return;
      }
      if (e.touches.length !== 1 || pinch) { lastY = null; return; }
      lastY = startY = e.touches[0].clientY;
      lastX = e.touches[0].clientX;
      lastT = e.timeStamp;
      moved = false;
      acc = 0;
      v = 0;
    }, { capture: true, passive: true });

    box.addEventListener("touchmove", function (e) {
      if (pinch && e.touches.length === 2) {
        var a = pt(e.touches[0]), b = pt(e.touches[1]);
        var m = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 };
        var sc = Math.min(ZOOM_MAX, Math.max(1, pinch.s0 * dist(a, b) / pinch.d0));
        // keep the point that was under the fingers under them
        var cx = (pinch.m0.x - pinch.t0.x) / pinch.s0, cy = (pinch.m0.y - pinch.t0.y) / pinch.s0;
        setZoom(sc, m.x - cx * sc, m.y - cy * sc);
        e.preventDefault();
        e.stopPropagation();
        return;
      }
      if (lastY == null || e.touches.length !== 1) return;
      var y = e.touches[0].clientY, x = e.touches[0].clientX;
      if (!moved && Math.abs(y - startY) < TAP_SLOP && Math.abs(x - lastX) < TAP_SLOP) return; // still a tap
      moved = true;
      var dy = y - lastY, dx = x - lastX, dt = Math.max(1, e.timeStamp - lastT);
      lastY = y;
      lastX = x;
      lastT = e.timeStamp;
      if (zoom.s > 1) {
        var py = zoom.y;
        setZoom(zoom.s, zoom.x + dx, zoom.y + dy);
        dy -= zoom.y - py; // what the pan could not take scrolls the text
      }
      v = 0.8 * (dy / dt) + 0.2 * v; // px per ms, smoothed
      if (dy) applyDy(dy);
      e.preventDefault();
      e.stopPropagation();
    }, { capture: true, passive: false });

    box.addEventListener("touchcancel", function () { pinch = null; lastY = null; if (zoom.s < 1.05) setZoom(1, 0, 0); }, { capture: true, passive: true });
    box.addEventListener("touchend", function (e) {
      if (pinch) {
        if (e.touches.length === 0) { pinch = null; if (zoom.s < 1.05) setZoom(1, 0, 0); }
        e.stopPropagation();
        return;
      }
      if (lastY == null) return;
      lastY = null;
      if (!moved) return;
      e.stopPropagation();
      if (e.timeStamp - lastT > 80 || Math.abs(v) < MIN_FLING) return; // the finger stopped before lifting
      var prev = performance.now();
      var step = function (now) {
        var dt = Math.min(64, now - prev);
        prev = now;
        applyDy(v * dt);
        v *= Math.pow(FRICTION, dt / 16);
        fling = Math.abs(v) > 0.05 ? requestAnimationFrame(step) : 0;
      };
      fling = requestAnimationFrame(step);
    }, { capture: true, passive: false });
  })();
  function send(bytes) { if (ws && ws.readyState === 1 && !exited) ws.send(bytes); }

  function scheduleFit() {
    if (!term || $("term").hidden) return;
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(doFit, 80);
  }
  function doFit() {
    if (!term) return;
    if (current && current.kind === "external") {
      // A PC console keeps its own size: re-flowed to this width, or drawn at the PC size (font fitted).
      var was = !!rf;
      if (chooseReflow()) return;
      if (was) { connect(); return; } // back to the PC's own layout: fresh frames at the PC size
      fitFont();
      return;
    }
    BASE_FONT = autoFont();
    setFont(BASE_FONT);
    try { fit.fit(); } catch (e) { return; }
    sendSize();
    fitFont();
  }

  function setFont(px) { if (term.options.fontSize !== px) term.options.fontSize = px; }

  /**
   * The PC session's size can be larger than this window (a PC console, or a PC tab typed in last): shrink the font
   * until all of its rows and columns fit, so the bottom rows (Claude Code's input box and status line) stay visible.
   */
  function fitFont() {
    shrinkFont();
    alignBottom();
  }

  /** Still taller than the window at the smallest font: show the bottom rows (where the input is), cut the top ones. */
  function alignBottom() {
    var el = term.element;
    if (!el) return;
    el.style.transform = "";
    var screen = el.querySelector(".xterm-screen");
    if (!screen) return;
    var over = screen.getBoundingClientRect().bottom - $("term-box").getBoundingClientRect().bottom;
    if (over > 0.5) el.style.transform = "translateY(" + (-Math.ceil(over)) + "px)";
  }

  function shrinkFont() {
    if (!serverSize) return;
    setFont(BASE_FONT);
    var p = fit.proposeDimensions();
    if (!p || (p.cols >= serverSize.cols && p.rows >= serverSize.rows)) return;
    var f = Math.max(MIN_FONT, Math.floor(BASE_FONT * Math.min(p.cols / serverSize.cols, p.rows / serverSize.rows) * 2) / 2);
    setFont(f);
    p = fit.proposeDimensions();
    while (p && f > MIN_FONT && (p.cols < serverSize.cols || p.rows < serverSize.rows)) {
      f -= 0.5;
      setFont(f);
      p = fit.proposeDimensions();
    }
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
    serverSize = null;
    setZoom(1, 0, 0);
    if (rf) { rf = null; term.options.scrollback = 10000; }
    BASE_FONT = autoFont();
    setFont(BASE_FONT);
    try { fit.fit(); } catch (e) { /* hidden */ }
    retry = 0;
    exited = false;
    connect();
    var guest = !!(info && info.share);
    $("tool-file").hidden = guest; // someone else's PC: no files into it
    $("tool-image").hidden = guest && info.share.readOnly;
    startQuick();
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
      checkHostVersion();
      retry = 0;
      term.reset();
      status("연결됨");
      lastSent = term.cols + "x" + term.rows;
    };
    sock.onmessage = function (ev) {
      if (ws !== sock) return;
      if (typeof ev.data !== "string") {
        var bytes = new Uint8Array(ev.data);
        if (rf) {
          var shifted = rf.h.accept(bytes);
          if (shifted >= 0) {
            if (rf.scroll > 0) rf.scroll += rf.h.wrappedCount(shifted, rf.cols); // keep the lines being read in place
            drawReflow();
            return;
          }
        }
        term.write(bytes);
        return;
      }
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
          if (rf) { rf.h.replaceHistory(m.lines || []); drawReflow(); break; }
          (m.lines || []).forEach(function (l) { term.write(l + "\r\n"); });
          break;
        case "size":
          // The PC session's size (several windows share it; it follows the one typed in last): draw at that size
          // so lines wrap as they do there. This window's own size goes up in doFit.
          if (m.cols > 0 && m.rows > 0) {
            serverSize = { cols: m.cols, rows: m.rows };
            if (current.kind === "external" && chooseReflow()) break;
            if (m.cols !== term.cols || m.rows !== term.rows) term.resize(m.cols, m.rows);
            fitFont();
          }
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
    if (reason === "Failed to fetch" || reason === "Load failed") reason = "네트워크 오류";
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
    stopQuick();
    current = null;
    setCtrl(false);
    setAlt(false);
  }

  $("back").addEventListener("click", function () { openList(); });

  window.addEventListener("online", function () {
    if (current && !ws && !exited && !$("term").hidden) { retry = 0; connect(); }
  });

  document.addEventListener("visibilitychange", function () { if (!document.hidden) checkHostVersion(); });
  document.addEventListener("visibilitychange", function () {
    // Back from sleep / another tab: reconnect at once instead of waiting out the backoff.
    if (!document.hidden && current && !ws && !exited && !$("term").hidden) { retry = 0; connect(); }
  });

  // On-screen keys for touch screens (iPhone / iPad Safari have no Esc, Tab, Ctrl or arrows).
  // Same keys as the phone app's key bar. Ctrl and Alt latch for the next key (on-screen keyboard or key bar).
  var KEYS = { esc: "\x1b", tab: "\t", ctrlc: "\x03", enter: "\r", pgup: "\x1b[5~", pgdn: "\x1b[6~" };
  var CHARS = { slash: "/", pipe: "|", tilde: "~", minus: "-" };
  var CURSOR = { up: "A", down: "B", right: "C", left: "D", home: "H", end: "F" };
  var touch = window.matchMedia && window.matchMedia("(pointer: coarse)").matches;
  if (touch) $("keybar").hidden = false;
  // Keep the key bar from pressing the terminal's focus away (the on-screen keyboard would close).
  $("keybar").addEventListener("pointerdown", function (e) { if (e.target.closest("button")) e.preventDefault(); });
  $("keybar").addEventListener("click", function (e) {
    var b = e.target.closest("button");
    if (!b || !term) return;
    var k = b.getAttribute("data-key");
    if (k === "ctrl") { setCtrl(!ctrlArmed); term.focus(); return; }
    if (k === "alt") { setAlt(!altArmed); term.focus(); return; }
    if (k === "snippets") { showSnippets(); return; }
    var seq = KEYS[k];
    if (CURSOR[k]) seq = (term.modes && term.modes.applicationCursorKeysMode ? "\x1bO" : "\x1b[") + CURSOR[k];
    if (CHARS[k]) seq = applyCtrl(CHARS[k]);
    if (seq) sendInput(applyAlt(seq));
    term.focus();
  });

  // The on-screen keyboard: the terminal fills only what is still visible, so the key bar sits right above it
  // (Android Chrome resizes with interactive-widget=resizes-content; iOS Safari only shrinks the visual viewport).
  if (window.visualViewport) {
    var fitViewport = function () {
      var vv = window.visualViewport;
      var t = $("term");
      if (t.hidden) return;
      t.style.height = Math.round(vv.height) + "px";
      t.style.transform = vv.offsetTop ? "translateY(" + Math.round(vv.offsetTop) + "px)" : "";
      scheduleFit();
    };
    window.visualViewport.addEventListener("resize", fitViewport);
    window.visualViewport.addEventListener("scroll", fitViewport);
  }

  // ---------- terminal tools: photo, file upload, fit, keyboard (the phone app's top bar) ----------
  var toastTimer = null;
  function toast(msg, bad) {
    var t = $("toast");
    t.textContent = msg;
    t.classList.toggle("bad", !!bad);
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { t.hidden = true; }, bad ? 6000 : 3000);
  }

  /** POST a raw body (image / file) with the device token; resolves to the JSON reply, rejects with ApiError. */
  function rawPost(path, body, type) {
    return fetch(path, { method: "POST", headers: { Authorization: "Bearer " + token, "Content-Type": type || "application/octet-stream" }, body: body })
      .then(function (r) {
        return r.text().then(function (t) {
          var j = null;
          try { j = t ? JSON.parse(t) : null; } catch (e) { /* not json */ }
          if (r.status === 401) return unpaired();
          if (!r.ok) throw new ApiError(r.status, (j && j.error) || ("HTTP " + r.status));
          return j;
        });
      });
  }

  function sleep(ms) { return new Promise(function (res) { setTimeout(res, ms); }); }

  var MAX_SIDE = 2560, MAX_PNG_BYTES = 2 * 1024 * 1024;

  /** Any image the browser can decode (HEIC on iPhone included) → PNG, or JPEG when the PNG would be large. */
  function encodeImage(file) {
    return new Promise(function (resolve, reject) {
      var url = URL.createObjectURL(file);
      var img = new Image();
      img.onload = function () {
        URL.revokeObjectURL(url);
        var w = img.naturalWidth, h = img.naturalHeight, k = Math.min(1, MAX_SIDE / Math.max(w, h));
        var c = document.createElement("canvas");
        c.width = Math.max(1, Math.round(w * k));
        c.height = Math.max(1, Math.round(h * k));
        c.getContext("2d").drawImage(img, 0, 0, c.width, c.height);
        c.toBlob(function (png) {
          if (png && png.size <= MAX_PNG_BYTES) return resolve({ blob: png, type: "image/png" });
          c.toBlob(function (jpg) { jpg ? resolve({ blob: jpg, type: "image/jpeg" }) : reject(new Error("이미지 변환 실패")); }, "image/jpeg", 0.9);
        }, "image/png");
      };
      img.onerror = function () { URL.revokeObjectURL(url); reject(new Error("이미지를 읽을 수 없음: " + file.name)); };
      img.src = url;
    });
  }

  /** Each image goes to the PC clipboard, then Alt+V pastes it into the AI CLI (Claude Code attaches it). */
  function pasteImages(files) {
    var list = Array.prototype.slice.call(files);
    var i = 0;
    $("tool-image").disabled = true;
    function next() {
      if (i >= list.length) return Promise.resolve();
      var f = list[i++];
      toast("이미지 보내는 중 " + i + "/" + list.length + "…");
      return encodeImage(f).then(function (e) { return rawPost("/api/clipboard/image", e.blob, e.type).then(function () { return e; }); })
        .then(function (e) {
          sendInput("\x1bv");
          toast("이미지 붙임 " + i + "/" + list.length + " (" + Math.round(e.blob.size / 1024) + " KB)");
          return i < list.length ? sleep(1500).then(next) : null;
        });
    }
    next().catch(function (err) { if (err.status !== 401) toast("이미지 보내기 실패: " + (err.message || err), true); })
      .then(function () { $("tool-image").disabled = false; term && term.focus(); });
  }

  /** Files into the session's folder on the PC (asks before replacing one that exists). */
  function uploadFiles(files) {
    var dir = current && current.cwd;
    if (!dir) { toast("세션 폴더를 모름: 올릴 수 없음", true); return; }
    var list = Array.prototype.slice.call(files);
    var done = [];
    $("tool-file").disabled = true;
    // The file is read once: a request the PC refused (409, file exists) leaves the picked File unreadable in Chrome,
    // so the retry sends a copy of the bytes.
    function put(f, overwrite, bytes) {
      var path = "/api/files/upload?dir=" + encodeURIComponent(dir) + "&name=" + encodeURIComponent(f.name) + (overwrite ? "&overwrite=1" : "");
      return rawPost(path, new Blob([bytes])).catch(function (err) {
        if (err.status === 409 && !overwrite && confirm("'" + f.name + "' 파일이 이미 있음. 바꿀까?")) return put(f, true, bytes);
        if (err.status === 409) return null;
        throw err;
      });
    }
    var chain = Promise.resolve();
    list.forEach(function (f, n) {
      chain = chain.then(function () {
        toast("올리는 중 " + (n + 1) + "/" + list.length + ": " + f.name);
        return f.arrayBuffer().then(function (bytes) { return put(f, false, bytes); }).then(function (r) { if (r) done.push(f.name); });
      });
    });
    chain.then(function () {
      toast(done.length ? "올림 " + done.length + "개 → " + dir : "올린 파일 없음");
    }).catch(function (err) {
      if (err.status !== 401) toast("파일 올리기 실패: " + (err.message || err), true);
    }).then(function () { $("tool-file").disabled = false; });
  }

  $("tool-image").addEventListener("click", function () { $("pick-image").click(); });
  $("tool-file").addEventListener("click", function () { $("pick-file").click(); });
  $("pick-image").addEventListener("change", function (e) { if (e.target.files.length) pasteImages(e.target.files); e.target.value = ""; });
  $("pick-file").addEventListener("change", function (e) { if (e.target.files.length) uploadFiles(e.target.files); e.target.value = ""; });
  $("tool-fit").addEventListener("click", function () { setZoom(1, 0, 0); lastSent = ""; doFit(); if (term) term.scrollToBottom(); });

  // File explorer at the session's current folder (asked fresh: the shell may have cd'd since the list was read).
  if (window.PsFiles) {
    window.PsFiles.init({
      api: api, rawPost: rawPost, toast: toast, el: el, button: button, ApiError: ApiError,
      token: function () { return token; },
      show: show,
      back: function () {
        show("term");
        scheduleFit();
        refocusTerm();
      },
    });
  }
  // Phones: the app's layout — ⋮ menu in the bar, + button for a new session.
  $("fab").addEventListener("click", function () { $("new-session").click(); });
  $("list-menu").addEventListener("click", function () {
    var items = [["새로 고침", function () { $("refresh").click(); }], ["웹 보기 (PC localhost)", function () { stopPolling(); openWeb(0, openList); }]];
    if (!$("install").hidden) items.push(["앱 설치", function () { $("install").click(); }]);
    items.push(["연결 해제", function () { $("unpair").click(); }, "danger"]);
    window.PsFiles.sheet(info && info.name || "PocketShell", items);
  });

  // Session list (first screen): the PC's drives and folders.
  $("list-files").addEventListener("click", function () {
    stopPolling();
    window.PsFiles.open("", "PC", function () { openList(); });
  });
  $("tool-files").addEventListener("click", function () {
    if (!current) return;
    var id = current.id;
    api("GET", "/api/sessions/" + encodeURIComponent(id)).then(function (s) {
      var cwd = (s && s.cwd) || (current && current.cwd);
      if (!cwd) { toast("세션 폴더를 모름", true); return; }
      window.PsFiles.open(cwd, displayTitle(current), null);
    }).catch(function (err) {
      if (err.status === 401) return;
      if (current && current.cwd) window.PsFiles.open(current.cwd, displayTitle(current), null);
      else toast("세션 폴더를 모름: " + err.message, true);
    });
  });

  // ---------- ⋮ terminal menu (the phone app's options, the ones a browser can do) ----------
  $("tool-more").addEventListener("click", function () {
    if (!current) return;
    var items = [
      ["정보", showTermInfo],
      ["텍스트 선택·복사", function () { openTextView(false); }],
      ["검색", function () { openTextView(true); }],
    ];
    if (!$("keybar").hidden) items.push(["자주 쓰는 문구", showSnippets]);
    items.push(["글자 크기", showFontSizes]);
    if (!(info && info.share)) items.push(["웹 보기 (PC localhost)", function () { openWebFromTerm(); }]);
    window.PsFiles.sheet(displayTitle(current), items);
  });

  function showTermInfo() {
    var lines = [current.title || displayTitle(current)];
    if (current.cwd) lines.push(current.cwd);
    lines.push($("term-status").textContent || "연결 안 됨");
    if (serverSize) lines.push("PC 크기 " + serverSize.cols + "×" + serverSize.rows);
    if (term) lines.push("이 화면 " + term.cols + "×" + term.rows + " · 글자 " + term.options.fontSize + "px");
    if (rf) lines.push("폰 폭으로 다시 배치 중");
    window.PsFiles.sheet("정보", lines.map(function (l) { return [l, refocusTerm, "info"]; }));
  }

  function showFontSizes() {
    var now = storageGet(FONT_KEY);
    var pick = function (v) {
      return function () { storageSet(FONT_KEY, v); lastSent = ""; doFit(); if (term && !rf) term.scrollToBottom(); refocusTerm(); };
    };
    var items = [[(now ? "" : "✓ ") + "자동 (화면 크기에 맞춤)", pick(null)]];
    [11, 12, 13, 14, 15, 16, 18, 20].forEach(function (px) {
      items.push([(now === String(px) ? "✓ " : "") + px + "px", pick(String(px))]);
    });
    window.PsFiles.sheet("글자 크기", items);
  }

  /** The terminal's text (scrollback + screen) as plain lines; wrapped rows are joined back. */
  function terminalText() {
    if (rf) return rf.h.plainText();
    if (!term) return "";
    var b = term.buffer.active, out = [];
    for (var i = 0; i < b.length; i++) {
      var line = b.getLine(i);
      if (!line) continue;
      var t = line.translateToString(true);
      if (line.isWrapped && out.length) out[out.length - 1] += t; else out.push(t);
    }
    while (out.length && !out[out.length - 1].trim()) out.pop();
    return out.join("\n");
  }

  var textView = { text: "", marks: [], at: -1, q: null };
  function openTextView(search) {
    textView.text = terminalText();
    textView.marks = [];
    textView.at = -1;
    textView.q = null;
    $("text-view-title").textContent = search ? "검색" : "텍스트 선택·복사";
    $("text-view-sub").textContent = textView.text ? textView.text.split("\n").length + "줄 · 길게 눌러 선택" : "";
    $("text-search-input").value = "";
    renderTextView("");
    $("text-view").showModal();
    var body = $("text-view-body");
    body.scrollTop = body.scrollHeight; // the newest lines, as on the terminal
    if (search) $("text-search-input").focus(); else $("text-view-close").focus();
  }
  function renderTextView(q) {
    var pre = $("text-view-pre");
    pre.textContent = "";
    textView.marks = [];
    var text = textView.text;
    if (!text) { pre.textContent = "(내용 없음)"; return; }
    if (!q) { pre.textContent = text; return; }
    var low = text.toLowerCase(), ql = q.toLowerCase(), from = 0, i;
    while ((i = low.indexOf(ql, from)) >= 0 && textView.marks.length < 1000) {
      if (i > from) pre.appendChild(document.createTextNode(text.slice(from, i)));
      var m = el("mark", null, text.slice(i, i + q.length));
      pre.appendChild(m);
      textView.marks.push(m);
      from = i + q.length;
    }
    pre.appendChild(document.createTextNode(text.slice(from)));
  }
  function findIn(step) {
    var q = $("text-search-input").value;
    if (q !== textView.q) { textView.q = q; renderTextView(q); textView.at = step > 0 ? -1 : textView.marks.length; }
    var n = textView.marks.length;
    if (!q) { $("text-view-sub").textContent = ""; return; }
    if (!n) { $("text-view-sub").textContent = "찾는 글자 없음"; return; }
    if (textView.marks[textView.at]) textView.marks[textView.at].classList.remove("cur");
    // from the end backwards by default (newest first), as the phone app's search
    textView.at = step > 0 ? (textView.at + 1) % n : (textView.at - 1 + n) % n;
    var m = textView.marks[textView.at];
    m.classList.add("cur");
    m.scrollIntoView({ block: "center" });
    $("text-view-sub").textContent = (textView.at + 1) + " / " + n + (n >= 1000 ? "+" : "");
  }
  $("text-search").addEventListener("submit", function (e) { e.preventDefault(); findIn(-1); });
  $("text-search-next").addEventListener("click", function (e) { e.preventDefault(); findIn(-1); });
  $("text-search-prev").addEventListener("click", function () { findIn(1); });
  $("text-search-input").addEventListener("input", function () { textView.q = null; findIn(-1); });
  $("text-view-copy").addEventListener("click", function () {
    var sel = window.getSelection && String(window.getSelection());
    var t = sel && $("text-view-pre").contains(window.getSelection().anchorNode) ? sel : textView.text;
    copyText(t).then(function () { toast(t === textView.text ? "전체 복사됨" : "선택한 글자 복사됨"); },
      function () { toast("복사 못 함: 길게 눌러 선택한 뒤 복사", true); });
  });
  $("text-view-close").addEventListener("click", function () { $("text-view").close(); });
  $("text-view").addEventListener("close", function () { textView.text = ""; $("text-view-pre").textContent = ""; refocusTerm(); });
  function copyText(t) {
    if (navigator.clipboard && navigator.clipboard.writeText) return navigator.clipboard.writeText(t);
    return new Promise(function (ok, fail) {
      var ta = document.createElement("textarea");
      ta.value = t;
      $("text-view").appendChild(ta);
      ta.select();
      var done = false;
      try { done = document.execCommand("copy"); } catch (e) { done = false; }
      ta.remove();
      if (done) ok(); else fail(new Error("copy"));
    });
  }

  // ---------- 웹 보기: the PC's localhost dev servers, each through its own HTTPS port of the host (WebForward) ----------
  var GLOBE_SVG = '<svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="9"/><path d="M3 12h18"/><path d="M12 3a14 14 0 0 1 0 18a14 14 0 0 1 0-18z"/></svg>';
  var WEB_KEY = "pocketshell.webRecent";
  var web = { back: null, url: "", target: 0 };
  function webRecent() { return (storageGet(WEB_KEY) || "").split(",").map(Number).filter(function (p) { return p > 0 && p < 65536; }); }
  function openWebFromTerm() {
    var ports = current && current.webPorts;
    openWeb(0, function () { show("term"); scheduleFit(); refocusTerm(); }, ports);
  }
  /** Opens the web view; port 0 shows the list of the PC's web pages first. */
  function openWeb(port, back, prefer) {
    web.back = back;
    $("web-back").textContent = back === openList ? "← 목록" : "← 터미널";
    show("web");
    if (port) webGo(port, "/"); else webPick(prefer);
  }
  function webPick(prefer) {
    web.url = ""; web.target = 0;
    var frame = $("web-frame");
    frame.hidden = true;
    frame.removeAttribute("src");
    $("web-pick").hidden = false;
    $("web-addr").value = "";
    var box = $("web-ports");
    var add = function (list, seen, label) {
      list.forEach(function (p) {
        if (seen[p]) return;
        seen[p] = true;
        box.appendChild(button("localhost:" + p + (label ? "  · " + label : ""), null, function () { webGo(p, "/"); }));
      });
    };
    var draw = function (running) {
      box.textContent = "";
      var seen = {};
      add(prefer || [], seen, "이 세션");
      add(running, seen, "");
      add(webRecent(), seen, "최근");
      if (!box.children.length) box.appendChild(el("p", "muted", "실행 중인 웹 서버 없음"));
    };
    draw([]);
    api("GET", "/api/web/ports").then(function (r) { if (!$("web").hidden && !web.target) draw((r && r.ports) || []); })
      .catch(function (err) { if (err.status !== 401) toast("목록을 못 불러옴: " + err.message, true); });
  }
  /** "3000", "localhost:3000/x", "http://127.0.0.1:3000/x" → [3000, "/x"]; null when it names no localhost port. */
  function parseWebAddr(text) {
    var t = String(text || "").trim().replace(/^https?:\/\//i, "");
    var m = /^(?:(?:localhost|127\.0\.0\.1|\[::1\]):)?(\d{1,5})(\/.*)?$/i.exec(t);
    if (!m) return null;
    var port = +m[1];
    return port > 0 && port < 65536 ? [port, m[2] || "/"] : null;
  }
  function webGo(target, path) {
    $("web-addr").value = "localhost:" + target + (path === "/" ? "" : path);
    api("POST", "/api/web/open", { port: target }).then(function (r) {
      if (!r || $("web").hidden) return;
      web.target = target;
      web.url = "https://" + location.hostname + ":" + r.port + path;
      storageSet(WEB_KEY, [target].concat(webRecent().filter(function (p) { return p !== target; })).slice(0, 6).join(","));
      $("web-pick").hidden = true;
      var frame = $("web-frame");
      frame.hidden = false;
      frame.src = web.url;
      // By IP the PC's own certificate is not trusted, and a frame cannot show the browser's warning.
      if (/^[\d.]+$|:/.test(location.hostname)) toast("IP 주소 접속: 안 열리면 ↗ 새 탭에서 열기", false);
    }).catch(function (err) { if (err.status !== 401) toast("웹 보기 실패: " + err.message, true); });
  }
  $("web-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var a = parseWebAddr($("web-addr").value);
    if (!a) { toast("PC의 localhost 포트 번호 입력 (예: 3000, localhost:5173/admin)", true); return; }
    $("web-addr").blur();
    webGo(a[0], a[1]);
  });
  $("web-back").addEventListener("click", function () {
    var f = $("web-frame");
    f.hidden = true;
    f.removeAttribute("src");
    var back = web.back || openList;
    web.back = null;
    back();
  });
  $("web-reload").addEventListener("click", function () {
    if (!web.url) { webPick(); return; }
    var f = $("web-frame");
    f.src = "about:blank";
    setTimeout(function () { f.src = web.url; }, 0);
  });
  $("web-tab").addEventListener("click", function () {
    if (web.url) window.open(web.url, "_blank", "noopener");
    else toast("먼저 포트를 고르기", true);
  });

  // ---------- 자주 쓰는 문구 (saved phrases, this browser): typed into the input line, sent with Enter by the user ----------
  var SNIP_KEY = "pocketshell.snippets", SNIP_MAX = 20, SNIP_LEN = 200;
  var SNIP_DEFAULTS = ["계속해", "테스트 돌려줘", "커밋해"];
  function parseSnippets(text) {
    var seen = {};
    return text.split(/\r?\n/).map(function (l) { return l.trim().slice(0, SNIP_LEN); })
      .filter(function (l) { if (!l || seen[l]) return false; seen[l] = true; return true; }).slice(0, SNIP_MAX);
  }
  function snippets() {
    var t = storageGet(SNIP_KEY);
    return t == null ? SNIP_DEFAULTS.slice() : parseSnippets(t);
  }
  function showSnippets() {
    var box = $("snip-items");
    box.textContent = "";
    var list = snippets();
    if (!list.length) box.appendChild(el("p", "muted", "저장된 문구 없음"));
    list.forEach(function (t) {
      box.appendChild(button(t, null, function () {
        $("snip-dialog").close();
        sendInput(t);
        refocusTerm();
      }));
    });
    $("snip-dialog").showModal();
  }
  $("snip-close").addEventListener("click", function () { $("snip-dialog").close(); refocusTerm(); });
  $("snip-edit").addEventListener("click", function () {
    $("snip-dialog").close();
    $("snip-text").value = snippets().join("\n");
    $("snip-edit-dialog").showModal();
    $("snip-text").focus();
  });
  $("snip-edit-cancel").addEventListener("click", function () { $("snip-edit-dialog").close(); refocusTerm(); });
  $("snip-edit-form").addEventListener("submit", function (e) {
    e.preventDefault();
    var list = parseSnippets($("snip-text").value);
    storageSet(SNIP_KEY, list.join("\n"));
    $("snip-edit-dialog").close();
    toast("문구 " + list.length + "개 저장");
    showSnippets();
  });
  $("tool-keyboard").addEventListener("click", function () {
    if (!term) return;
    var ta = term.textarea;
    if (ta && document.activeElement === ta) ta.blur(); else term.focus();
  });

  // ---------- quick replies (the AI CLI waits on a numbered question) ----------
  var quickTimer = null, quickKey = "";
  function stopQuick() { clearInterval(quickTimer); quickTimer = null; showQuick(null); }
  function startQuick() {
    stopQuick();
    if (info && info.share && info.share.readOnly) return;
    quickTimer = setInterval(pollQuick, 2500);
    pollQuick();
  }
  function pollQuick() {
    if (!current || $("term").hidden || document.hidden) return;
    var id = current.id;
    api("GET", "/api/sessions/" + encodeURIComponent(id)).then(function (s) {
      if (!current || current.id !== id) return;
      showQuick(s && s.agentState === "waiting" && s.prompt && s.prompt.options && s.prompt.options.length ? s.prompt : null);
    }).catch(function () { /* the terminal shows the connection state */ });
  }
  function showQuick(p) {
    var key = p ? JSON.stringify(p) : "";
    if (key === quickKey) return;
    quickKey = key;
    var bar = $("quickbar");
    bar.textContent = "";
    bar.hidden = !p;
    if (!p) { scheduleFit(); return; }
    if (p.question) bar.appendChild(el("p", "q", p.question));
    p.options.forEach(function (o, i) {
      if (!o.key && !o.enter) return;
      bar.appendChild(button((i + 1) + ".  " + (o.label || o.key), null, function () {
        if (o.key) sendInput(o.key);
        // Enter goes a moment later: a TUI reading keys and Enter in one chunk may take it for a paste.
        if (o.enter) setTimeout(function () { sendInput("\r"); }, o.key ? 120 : 0);
        showQuick(null);
        term.focus();
      }));
    });
    bar.appendChild(button("취소 (Esc)", null, function () { sendInput("\x1b"); showQuick(null); term.focus(); }));
    scheduleFit();
  }

  // ---------- home screen (install) ----------
  // Chrome / Edge offer their own install prompt (beforeinstallprompt) when they consider the page installable; with
  // the PC's self-signed certificate they usually do not, and Safari never does. Then the button shows the menu steps.
  var installPrompt = null;
  var ua = navigator.userAgent;
  var isIOS = /iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
  var isAndroid = /Android/.test(ua);
  var isEdge = /Edg\//.test(ua);
  var isChrome = /Chrome\/|CriOS\//.test(ua) && !isEdge;
  var isSafari = /Safari\//.test(ua) && !/Chrome\/|CriOS\/|Edg|Firefox|FxiOS/.test(ua);
  var isFirefox = /Firefox\/|FxiOS\//.test(ua);

  function standalone() {
    return (window.matchMedia && window.matchMedia("(display-mode: standalone)").matches) || navigator.standalone === true;
  }
  function updateInstallButton() { $("install").hidden = standalone(); }

  window.addEventListener("beforeinstallprompt", function (e) {
    e.preventDefault();
    installPrompt = e;
    updateInstallButton();
  });
  window.addEventListener("appinstalled", function () { installPrompt = null; $("install").hidden = true; });

  function installSteps() {
    if (isIOS) {
      return [
        isSafari ? "Safari 아래(또는 위) <b>공유</b> 버튼(□↑) 누르기" : "주소창의 <b>공유</b> 버튼(□↑) 누르기",
        "목록을 내려 <b>홈 화면에 추가</b> 선택",
        "이름 확인 후 <b>추가</b>",
      ];
    }
    if (isAndroid) return ["Chrome 오른쪽 위 <b>⋮</b> 메뉴", "<b>앱 설치</b> (또는 <b>홈 화면에 추가</b> → <b>설치</b>)", "<b>설치</b> — 앱 서랍과 홈 화면에 아이콘 생김"];
    if (isSafari) return ["메뉴 막대 <b>파일</b> → <b>Dock에 추가…</b> (macOS Sonoma 이상)", "이름 확인 후 <b>추가</b> — Dock과 Launchpad에 앱으로 생김"];
    if (isEdge) return ["오른쪽 위 <b>⋯</b> 메뉴", "<b>앱</b> → <b>이 사이트를 앱으로 설치</b>", "<b>설치</b>"];
    if (isChrome) {
      return [
        "주소창 오른쪽 <b>설치</b> 아이콘(⊕)이 있으면 누르기",
        "없으면 오른쪽 위 <b>⋮</b> → <b>전송, 저장, 공유</b> → <b>페이지를 앱으로 설치…</b> (또는 <b>바로가기 만들기…</b> → <b>창으로 열기</b> 체크)",
        "<b>설치</b> 또는 <b>만들기</b> — 바탕화면·Dock·시작 메뉴에 아이콘 생김",
      ];
    }
    if (isFirefox) return ["Firefox는 홈 화면 앱을 지원하지 않음", "Mac은 Safari나 Chrome으로 열어 추가"];
    return ["브라우저 메뉴의 <b>홈 화면에 추가</b> 또는 <b>앱 설치</b>"];
  }

  $("install").addEventListener("click", function () {
    if (installPrompt) {
      var p = installPrompt;
      installPrompt = null;
      p.prompt();
      p.userChoice.then(function (c) { if (c.outcome !== "accepted") installPrompt = p; }).catch(function () { /* dismissed */ });
      return;
    }
    var ol = $("install-steps");
    ol.textContent = "";
    var link = $("install-open");
    var web = info && info.webUrl;
    var onTrusted = web && new URL(web).origin === location.origin;
    var steps;
    if (web && !onTrusted) {
      // Installing needs the PC's trusted address (Tailscale name + Let's Encrypt certificate), not this IP address.
      steps = [
        "폰·Mac에서 <b>Tailscale</b> 켜기 (이 PC와 같은 계정)",
        "아래 <b>Tailscale 주소로 열기</b> → 같은 PIN으로 한 번 더 연결",
        "그 주소에서 다시 <b>앱 설치</b> 버튼",
      ];
      link.href = web;
      link.hidden = false;
      $("install-note").textContent = web;
    } else {
      steps = installSteps();
      link.hidden = true;
      $("install-note").textContent = web || isIOS || isAndroid || isSafari ? "" :
        "이 PC는 신뢰된 인증서 주소가 없어 Chrome·Edge 설치 버튼이 안 뜰 수 있음 (PC에서 Tailscale의 MagicDNS·HTTPS를 켜면 생김). 그때는 위 메뉴로 추가.";
    }
    steps.forEach(function (html) { var li = document.createElement("li"); li.innerHTML = html; ol.appendChild(li); });
    $("install-dialog").showModal();
  });

  // The service worker makes the page installable in Chrome (needs a trusted certificate: fails on the IP address).
  var byName = !/^[0-9.]+$/.test(location.hostname) && location.hostname.indexOf(":") < 0 && location.hostname.charAt(0) !== "[";
  if ("serviceWorker" in navigator && window.isSecureContext && byName) {
    navigator.serviceWorker.register("sw.js").catch(function () { /* untrusted certificate: no install, page still works */ });
  }
  $("install-close").addEventListener("click", function () { $("install-dialog").close(); });
  updateInstallButton();

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
