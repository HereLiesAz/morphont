// Minimal offline cache, network first: SHELL_FILES are precached on install; everything else from this
// site (including the compiled morphont.js/.wasm bundle) is cached the first time it's fetched, then served
// from the cache only when the network is unreachable. Only good same-origin responses are cached, so a
// transient 404/5xx can't replace a working offline copy, and other sites' requests (the font store, the
// update check) pass straight through.
const CACHE_NAME = "morphont-v2";
const SHELL_FILES = ["./", "index.html", "manifest.webmanifest"];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL_FILES)).catch(() => {}),
  );
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))),
    ),
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  if (new URL(request.url).origin !== self.location.origin) return;
  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok && response.type === "basic") {
          const copy = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(request, copy)).catch(() => {});
        }
        return response;
      })
      .catch(() => caches.match(request)),
  );
});
