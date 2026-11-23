/* PocketShell browser client: PC file explorer (the phone app's FileBrowserActivity).
   List / search / sort, open folders, preview text and images, download, upload, new folder / file,
   rename, duplicate, copy / cut / paste, delete (to the PC's Recycle Bin), multi-select.
   Uses /api/drives, /api/files, /api/files/content, /api/files/upload, /api/files/op.
   window.PsFiles.init(deps) once; window.PsFiles.open(path, title) shows it. */
(function () {
  "use strict";

  var d = null; // deps from app.js: { api, rawPost, toast, token, show, back, el, button, ApiError }
  var $ = function (id) { return document.getElementById(id); };
  var SORT_KEY = "pocketshell.fileSort", REV_KEY = "pocketshell.fileSortReverse";
  var MAX_TEXT_BYTES = 2 * 1024 * 1024, MAX_IMAGE_BYTES = 30 * 1024 * 1024;
  var IMAGE = { png: 1, jpg: 1, jpeg: 1, gif: 1, webp: 1, bmp: 1, svg: 0, heic: 1, heif: 1 };
  var BINARY = ("exe dll pdb obj o a lib so class jar zip 7z rar gz tar apk aab pdf doc docx xls xlsx ppt pptx hwp mp3 mp4 " +
    "mov avi wav ttf otf woff woff2 ico db sqlite bin iso msi").split(" ").reduce(function (m, e) { m[e] = 1; return m; }, {});

  var state = { path: "", title: "", entries: [], query: "", selected: {}, clip: null };

  function get(k) { try { return localStorage.getItem(k); } catch (e) { return null; } }
  function set(k, v) { try { localStorage.setItem(k, v); } catch (e) { /* private mode */ } }
  var sort = get(SORT_KEY) || "name";
  var reverse = get(REV_KEY) === "1";

  // ---------- pure helpers (same rules as the phone app's core/Files.kt) ----------
  function extension(name) { var i = name.lastIndexOf("."); return i > 0 ? name.slice(i + 1).toLowerCase() : ""; }

  function isDigit(c) { return c >= "0" && c <= "9"; }
  /** Case-insensitive, digit runs compared as numbers: file2 before file10. */
  function natural(a, b) {
    var i = 0, j = 0;
    while (i < a.length && j < b.length) {
      var ca = a[i], cb = b[j];
      if (isDigit(ca) && isDigit(cb)) {
        var si = i, sj = j;
        while (i < a.length && isDigit(a[i])) i++;
        while (j < b.length && isDigit(b[j])) j++;
        var na = a.slice(si, i).replace(/^0+/, ""), nb = b.slice(sj, j).replace(/^0+/, "");
        if (na.length !== nb.length) return na.length - nb.length;
        if (na !== nb) return na < nb ? -1 : 1;
      } else {
        var la = ca.toLowerCase(), lb = cb.toLowerCase();
        if (la !== lb) return la < lb ? -1 : 1;
        i++; j++;
      }
    }
    var rest = (a.length - i) - (b.length - j);
    if (rest) return rest;
    var x = a.toLowerCase(), y = b.toLowerCase();
    return x < y ? -1 : x > y ? 1 : (a < b ? -1 : a > b ? 1 : 0);
  }

  function sorted(entries) {
    var byName = function (a, b) { return natural(a.name, b.name); };
    var files = {
      name: byName,
      modified: function (a, b) { return a.modified < b.modified ? 1 : a.modified > b.modified ? -1 : byName(a, b); },
      size: function (a, b) { return b.size - a.size || byName(a, b); },
      type: function (a, b) { var x = extension(a.name), y = extension(b.name); return x < y ? -1 : x > y ? 1 : byName(a, b); },
    }[sort] || byName;
    var rev = function (f) { return function (a, b) { return -f(a, b); }; };
    var dirs = entries.filter(function (e) { return e.dir; }), rest = entries.filter(function (e) { return !e.dir; });
    var dirOrder = sort === "modified" ? files : byName;
    dirs.sort(reverse && (sort === "name" || sort === "modified") ? rev(dirOrder) : dirOrder);
    rest.sort(reverse ? rev(files) : files);
    return dirs.concat(rest);
  }

  function previewKind(e) {
    var ext = extension(e.name);
    if (IMAGE[ext]) return e.size <= MAX_IMAGE_BYTES ? "image" : "none";
    if (BINARY[ext]) return "none";
    return e.size <= MAX_TEXT_BYTES ? "text" : "none";
  }

  function join(dir, name) { return dir.replace(/[\\/]+$/, "") + "\\" + name; }
  function parent(path) {
    var t = path.replace(/[\\/]+$/, "");
    var i = Math.max(t.lastIndexOf("\\"), t.lastIndexOf("/"));
    if (i < 0) return null;
    var p = t.slice(0, i);
    return /^[A-Za-z]:$/.test(p) ? p + "\\" : (p || null);
  }
  function baseName(path) { var t = path.replace(/[\\/]+$/, ""); return t.slice(Math.max(t.lastIndexOf("\\"), t.lastIndexOf("/")) + 1) || t; }

  function sizeText(n) {
    if (n < 1024) return n + " B";
    if (n < 1048576) return (n / 1024).toFixed(n < 10240 ? 1 : 0) + " KB";
    if (n < 1073741824) return (n / 1048576).toFixed(1) + " MB";
    return (n / 1073741824).toFixed(1) + " GB";
  }
  function timeText(iso) {
    var t = new Date(iso);
    if (isNaN(t)) return "";
    var p = function (x) { return (x < 10 ? "0" : "") + x; };
    var now = new Date();
    var day = t.getFullYear() === now.getFullYear() ? (t.getMonth() + 1) + "-" + p(t.getDate()) : t.getFullYear() + "-" + p(t.getMonth() + 1) + "-" + p(t.getDate());
    return day + " " + p(t.getHours()) + ":" + p(t.getMinutes());
  }

  // ---------- loading and rendering ----------
  var onBack = null;
  /** path "" = the PC's places (all drives and known folders). back: where '←' returns (default: the terminal). */
  function open(path, title, back) {
    if (back !== undefined) onBack = back;
    $("files-back").textContent = onBack ? "← 목록" : "← 터미널";
    state.path = path;
    if (title != null) state.title = title;
    state.selected = {};
    state.query = "";
    $("files-search").value = "";
    d.show("files");
    load();
  }

  function load() {
    var path = state.path;
    $("files-path").textContent = path || "PC (드라이브 · 폴더)";
    $("files-list").textContent = "";
    $("files-msg").textContent = "불러오는 중…";
    $("files-up").disabled = !path;
    document.getElementById("files").classList.toggle("places", !path);
    if (!path) {
      d.api("GET", "/api/drives").then(function (r) {
        if (state.path !== "") return;
        state.entries = ((r && r.places) || []).map(function (p) { return { name: p.name, dir: true, size: 0, modified: "", place: p.path }; });
        render();
      }).catch(function (err) { if (err.status !== 401) { state.entries = []; render(); $("files-msg").textContent = "위치를 못 불러옴: " + (err.message || err); } });
      return;
    }
    d.api("GET", "/api/files?path=" + encodeURIComponent(path)).then(function (r) {
      if (state.path !== path) return;
      state.entries = (r && r.entries) || [];
      render();
    }).catch(function (err) {
      if (err.status === 401) return;
      state.entries = [];
      render();
      $("files-msg").textContent = "열 수 없음: " + (err.message || err);
    });
  }

  function selectedEntries() { return state.entries.filter(function (e) { return state.selected[e.name]; }); }
  function selecting() { return selectedEntries().length > 0; }

  function render() {
    var q = state.query.trim().toLowerCase();
    var all = state.entries;
    var shown = (state.path ? sorted : function (x) { return x; })(q ? all.filter(function (e) { return e.name.toLowerCase().indexOf(q) >= 0; }) : all);
    var dirs = all.filter(function (e) { return e.dir; }).length;
    $("files-summary").textContent = q ? "찾음 " + shown.length + " / " + all.length : "폴더 " + dirs + " · 파일 " + (all.length - dirs);
    $("files-msg").textContent = shown.length ? "" : (all.length ? "맞는 항목 없음" : "빈 폴더");
    var ul = $("files-list");
    ul.textContent = "";
    shown.forEach(function (e) { ul.appendChild(row(e)); });
    var sel = selectedEntries();
    $("files-selbar").hidden = !sel.length;
    $("files-selcount").textContent = sel.length + "개 선택";
    $("files-paste").hidden = !state.clip;
    $("files-paste").textContent = state.clip ? "붙여넣기 (" + state.clip.paths.length + ")" : "붙여넣기";
  }

  var ICON_DIR = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/></svg>';
  var ICON_FILE = '<svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M6 3h8l4 4v14H6z"/><path d="M14 3v4h4"/></svg>';

  function row(e) {
    var li = d.el("li", "frow" + (state.selected[e.name] ? " sel" : ""));
    var check = document.createElement("input");
    check.type = "checkbox";
    check.className = "fcheck";
    check.checked = !!state.selected[e.name];
    check.setAttribute("aria-label", "선택: " + e.name);
    check.addEventListener("change", function () { toggle(e); });
    li.appendChild(check);
    var main = d.el("button", "fmain");
    main.type = "button";
    var ic = d.el("span", "ficon" + (e.dir ? " dir" : ""));
    ic.innerHTML = e.dir ? ICON_DIR : ICON_FILE;
    main.appendChild(ic);
    var txt = d.el("span", "ftext");
    txt.appendChild(d.el("span", "fname", e.name));
    txt.appendChild(d.el("span", "fsub", e.place ? e.place : (e.dir ? "폴더" : sizeText(e.size)) + " · " + timeText(e.modified)));
    main.appendChild(txt);
    main.addEventListener("click", function () {
      if (selecting()) { toggle(e); return; }
      if (e.place) open(e.place); else if (e.dir) open(join(state.path, e.name)); else preview(e);
    });
    li.appendChild(main);
    li.appendChild(d.button("⋯", "ghost fmore", function () { actions(e); }, "메뉴: " + e.name));
    return li;
  }

  function toggle(e) {
    if (state.selected[e.name]) delete state.selected[e.name]; else state.selected[e.name] = true;
    render();
  }

  // ---------- item actions ----------
  function actions(e) {
    var path = join(state.path, e.name);
    var list = [];
    if (e.dir) list.push(["열기", function () { open(path); }]);
    else {
      if (previewKind(e) !== "none") list.push(["보기", function () { preview(e); }]);
      list.push(["다운로드", function () { download(path, e.name); }]);
    }
    list.push(["이름 바꾸기", function () { rename(e); }]);
    list.push(["복제", function () { duplicate(e); }]);
    list.push(["복사", function () { toClip([path], "copy"); }]);
    list.push(["잘라내기", function () { toClip([path], "move"); }]);
    if (e.dir && state.clip) list.push(["여기에 붙여넣기", function () { paste(path); }]);
    list.push(["삭제 (휴지통)", function () { remove([e]); }, "danger"]);
    list.push([state.selected[e.name] ? "선택 해제" : "선택", function () { toggle(e); }]);
    sheet(e.name, list);
  }

  function sheet(title, items) {
    var dlg = $("files-sheet");
    $("files-sheet-title").textContent = title;
    var box = $("files-sheet-items");
    box.textContent = "";
    items.forEach(function (it) {
      box.appendChild(d.button(it[0], it[2] || "", function () { dlg.close(); it[1](); }));
    });
    dlg.showModal();
  }

  /** Small text-input dialog; resolves with the trimmed text, or null when cancelled. */
  function ask(title, value, okLabel) {
    return new Promise(function (resolve) {
      var dlg = $("files-ask");
      $("files-ask-title").textContent = title;
      var input = $("files-ask-input");
      input.value = value || "";
      $("files-ask-ok").textContent = okLabel || "확인";
      var done = function (v) {
        dlg.removeEventListener("close", onClose);
        $("files-ask-form").onsubmit = null;
        resolve(v);
      };
      var onClose = function () { done(dlg.returnValue === "ok" ? input.value.trim() || null : null); };
      dlg.addEventListener("close", onClose);
      $("files-ask-form").onsubmit = function (ev) { ev.preventDefault(); dlg.close("ok"); };
      dlg.returnValue = "";
      dlg.showModal();
      input.focus();
      var dot = input.value.lastIndexOf(".");
      input.setSelectionRange(0, dot > 0 ? dot : input.value.length);
    });
  }

  function op(body, okText) {
    return d.api("POST", "/api/files/op", body).then(function (r) {
      var errs = (r && r.errors) || [];
      if (errs.length) d.toast(okText(r.done) + " · 실패 " + errs.length + ": " + errs[0], true);
      else d.toast(okText(r.done));
      return r;
    }).catch(function (err) {
      if (err.status !== 401) d.toast("실패: " + (err.message || err), true);
      throw err;
    }).then(function (r) { load(); return r; }, function () { load(); });
  }

  function rename(e) {
    ask("이름 바꾸기", e.name, "바꾸기").then(function (name) {
      if (!name || name === e.name) return;
      op({ op: "rename", paths: [join(state.path, e.name)], name: name }, function () { return "이름 바꿈: " + name; });
    });
  }

  function duplicate(e) {
    op({ op: "copy", paths: [join(state.path, e.name)], dest: state.path }, function () { return "복제함: " + e.name; });
  }

  function toClip(paths, kind) {
    state.clip = { paths: paths, kind: kind };
    state.selected = {};
    d.toast((kind === "copy" ? "복사" : "잘라내기") + " " + paths.length + "개 — 대상 폴더에서 '붙여넣기'");
    render();
  }

  function paste(dest) {
    var c = state.clip;
    if (!c) return;
    op({ op: c.kind, paths: c.paths, dest: dest || state.path }, function (n) { return (c.kind === "copy" ? "복사함 " : "옮김 ") + n + "개"; })
      .then(function () { if (c.kind === "move") state.clip = null; render(); });
  }

  function remove(entries) {
    var names = entries.map(function (e) { return e.name; });
    var msg = names.length === 1 ? "'" + names[0] + "'" : names.length + "개 항목";
    if (!confirm(msg + "을(를) PC 휴지통으로 옮길까?\n휴지통에서 되살릴 수 있음.")) return;
    state.selected = {};
    op({ op: "delete", paths: names.map(function (n) { return join(state.path, n); }) }, function (n) { return "휴지통으로 " + n + "개"; });
  }

  function newFolder() {
    ask("새 폴더", "새 폴더", "만들기").then(function (name) {
      if (name) op({ op: "mkdir", dest: state.path, name: name }, function () { return "폴더 만듦: " + name; });
    });
  }

  function newFile() {
    ask("새 파일", "새 파일.txt", "만들기").then(function (name) {
      if (!name) return;
      var path = "/api/files/upload?dir=" + encodeURIComponent(state.path) + "&name=" + encodeURIComponent(name);
      d.rawPost(path, new Blob([])).then(function () { d.toast("파일 만듦: " + name); load(); })
        .catch(function (err) { if (err.status !== 401) d.toast(err.status === 409 ? "같은 이름이 있음: " + name : "실패: " + err.message, true); });
    });
  }

  function upload(files) {
    var list = Array.prototype.slice.call(files), done = 0, dir = state.path;
    var chain = Promise.resolve();
    list.forEach(function (f, i) {
      chain = chain.then(function () {
        d.toast("올리는 중 " + (i + 1) + "/" + list.length + ": " + f.name);
        return f.arrayBuffer().then(function (bytes) {
          var put = function (ow) {
            return d.rawPost("/api/files/upload?dir=" + encodeURIComponent(dir) + "&name=" + encodeURIComponent(f.name) + (ow ? "&overwrite=1" : ""), new Blob([bytes]))
              .catch(function (err) {
                if (err.status === 409 && !ow && confirm("'" + f.name + "' 파일이 이미 있음. 바꿀까?")) return put(true);
                if (err.status === 409) return null;
                throw err;
              });
          };
          return put(false).then(function (r) { if (r) done++; });
        });
      });
    });
    chain.then(function () { d.toast(done ? "올림 " + done + "개" : "올린 파일 없음"); })
      .catch(function (err) { if (err.status !== 401) d.toast("올리기 실패: " + (err.message || err), true); })
      .then(load);
  }

  // ---------- content: preview and download ----------
  function fetchBlob(path) {
    return fetch("/api/files/content?path=" + encodeURIComponent(path), { headers: { Authorization: "Bearer " + d.token() } })
      .then(function (r) {
        if (r.status === 401) throw new d.ApiError(401, "unauthorized");
        if (!r.ok) return r.text().then(function (t) { var j = null; try { j = JSON.parse(t); } catch (e) { /* text */ } throw new d.ApiError(r.status, (j && j.error) || ("HTTP " + r.status)); });
        return r.blob();
      });
  }

  function download(path, name) {
    d.toast("받는 중: " + name);
    fetchBlob(path).then(function (blob) {
      var a = document.createElement("a");
      a.href = URL.createObjectURL(blob);
      a.download = name;
      document.body.appendChild(a);
      a.click();
      setTimeout(function () { URL.revokeObjectURL(a.href); a.remove(); }, 4000);
      d.toast("받음: " + name + " (" + sizeText(blob.size) + ")");
    }).catch(function (err) { if (err.status !== 401) d.toast("받기 실패: " + (err.message || err), true); });
  }

  /** UTF-8 (BOM stripped) when valid, else CP949 (Korean Windows files), else lossy UTF-8 — as the phone app. */
  function decodeText(buf) {
    var bytes = new Uint8Array(buf);
    var start = bytes.length >= 3 && bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf ? 3 : 0;
    var body = bytes.subarray(start);
    try { return new TextDecoder("utf-8", { fatal: true }).decode(body); } catch (e) { /* not UTF-8 */ }
    try { return new TextDecoder("euc-kr", { fatal: true }).decode(body); } catch (e) { /* not CP949 */ }
    return new TextDecoder("utf-8").decode(body);
  }

  var previewUrl = null;
  function preview(e) {
    var kind = previewKind(e), path = join(state.path, e.name);
    if (kind === "none") { download(path, e.name); return; }
    var dlg = $("files-preview");
    $("files-preview-title").textContent = e.name;
    $("files-preview-sub").textContent = sizeText(e.size) + " · " + timeText(e.modified);
    var box = $("files-preview-body");
    box.textContent = "불러오는 중…";
    $("files-preview-download").onclick = function () { download(path, e.name); };
    dlg.showModal();
    fetchBlob(path).then(function (blob) {
      if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; }
      box.textContent = "";
      if (kind === "image") {
        previewUrl = URL.createObjectURL(blob);
        var img = new Image();
        img.alt = e.name;
        img.src = previewUrl;
        img.onerror = function () { box.textContent = "이 브라우저에서 표시할 수 없는 이미지. 다운로드로 받기."; };
        box.appendChild(img);
        return;
      }
      return blob.arrayBuffer().then(function (buf) {
        var head = new Uint8Array(buf, 0, Math.min(buf.byteLength, 8192));
        for (var i = 0; i < head.length; i++) if (head[i] === 0) { box.textContent = "바이너리 파일. 다운로드로 받기."; return; }
        var pre = d.el("pre", "ftextview", decodeText(buf));
        box.appendChild(pre);
      });
    }).catch(function (err) { if (err.status !== 401) box.textContent = "열 수 없음: " + (err.message || err); });
  }

  // ---------- places and sort ----------
  function places() {
    d.api("GET", "/api/drives").then(function (r) {
      var items = ((r && r.places) || []).map(function (p) {
        return [p.name + "  —  " + p.path, function () { open(p.path); }];
      });
      sheet("PC 위치", items.length ? items : [["위치 없음", function () {}]]);
    }).catch(function (err) { if (err.status !== 401) d.toast("위치를 못 불러옴: " + err.message, true); });
  }

  function chooseSort() {
    var names = { name: "이름순", modified: "최근 수정순", size: "크기순", type: "종류순" };
    var items = Object.keys(names).map(function (k) {
      var label = names[k] + (k === sort ? (reverse ? " (역순) ✓" : " ✓") : "");
      return [label, function () { if (k === sort) reverse = !reverse; else { sort = k; reverse = false; } save(); render(); }];
    });
    items.push(["순서 뒤집기", function () { reverse = !reverse; save(); render(); }]);
    sheet("정렬", items);
  }
  function save() { set(SORT_KEY, sort); set(REV_KEY, reverse ? "1" : "0"); }

  // ---------- wiring ----------
  function init(deps) {
    d = deps;
    $("files-back").addEventListener("click", function () { if (onBack) onBack(); else d.back(); });
    $("files-up").addEventListener("click", function () { if (state.path) open(parent(state.path) || ""); }); // a drive's top → PC places
    $("files-places").addEventListener("click", places);
    $("files-sort").addEventListener("click", chooseSort);
    $("files-refresh").addEventListener("click", load);
    $("files-upload").addEventListener("click", function () { $("files-pick").click(); });
    $("files-pick").addEventListener("change", function (e) { if (e.target.files.length) upload(e.target.files); e.target.value = ""; });
    $("files-newfolder").addEventListener("click", newFolder);
    $("files-newfile").addEventListener("click", newFile);
    $("files-paste").addEventListener("click", function () { paste(state.path); });
    $("files-search").addEventListener("input", function (e) { state.query = e.target.value; render(); });
    $("files-sel-clear").addEventListener("click", function () { state.selected = {}; render(); });
    $("files-sel-all").addEventListener("click", function () { state.entries.forEach(function (e) { state.selected[e.name] = true; }); render(); });
    $("files-sel-copy").addEventListener("click", function () { toClip(selectedEntries().map(function (e) { return join(state.path, e.name); }), "copy"); });
    $("files-sel-cut").addEventListener("click", function () { toClip(selectedEntries().map(function (e) { return join(state.path, e.name); }), "move"); });
    $("files-sel-delete").addEventListener("click", function () { remove(selectedEntries()); });
    $("files-preview-close").addEventListener("click", function () { $("files-preview").close(); });
    $("files-preview").addEventListener("close", function () { if (previewUrl) { URL.revokeObjectURL(previewUrl); previewUrl = null; } });
    $("files-sheet-cancel").addEventListener("click", function () { $("files-sheet").close(); });
    $("files-ask-cancel").addEventListener("click", function () { $("files-ask").close(""); });
  }

  window.PsFiles = { init: init, open: open, sheet: function (t, items) { sheet(t, items); }, _test: { natural: natural, sorted: function (e, s, r) { sort = s; reverse = r; return sorted(e); }, parent: parent, previewKind: previewKind } };
})();
