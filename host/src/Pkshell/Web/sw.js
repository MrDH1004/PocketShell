/* PocketShell web app service worker: makes the page installable and opens the shell when the PC is briefly
   unreachable. Page files are fetched from the PC first (a host update shows at once); /api and /ws never touch it. */
"use strict";
var CACHE = "pocketshell-shell-v2";
var SHELL = ["./", "app.js", "app.css", "manifest.json", "vendor/xterm.js", "vendor/xterm.css", "vendor/addon-fit.js",
  "vendor/addon-web-links.js", "reflow.js", "icon-192.png", "icon-512.png", "favicon.ico"];

self.addEventListener("install", function (e) {
  e.waitUntil(caches.open(CACHE).then(function (c) { return c.addAll(SHELL); }).then(function () { return self.skipWaiting(); }));
});

self.addEventListener("activate", function (e) {
  e.waitUntil(caches.keys().then(function (keys) {
    return Promise.all(keys.filter(function (k) { return k !== CACHE; }).map(function (k) { return caches.delete(k); }));
  }).then(function () { return self.clients.claim(); }));
});

self.addEventListener("fetch", function (e) {
  var req = e.request;
  if (req.method !== "GET") return;
  var url = new URL(req.url);
  if (url.origin !== self.location.origin || url.pathname.indexOf("/api/") === 0 || url.pathname.indexOf("/ws/") === 0) return;
  e.respondWith(fetch(req).then(function (res) {
    if (res.ok) {
      var copy = res.clone();
      caches.open(CACHE).then(function (c) { c.put(req.mode === "navigate" ? "./" : req, copy); });
    }
    return res;
  }).catch(function () {
    return caches.match(req.mode === "navigate" ? "./" : req).then(function (hit) { return hit || Response.error(); });
  }));
});
