// Trazo Side Chat: el service worker guarda la cáscara para que la app instalada abra
// aunque la tablet esté apagada y muestre «Abrí Trazo en la tablet», en vez del error del navegador.
var SHELL = 'trazo-shell-v1';
var FILES = ['/', '/app.js', '/app.css', '/icon-192.png', '/vendor/katex/katex.min.js', '/vendor/katex/katex.min.css'];

self.addEventListener('install', function (e) {
  self.skipWaiting();
  e.waitUntil(caches.open(SHELL).then(function (c) {
    return c.addAll(FILES);
  }));
});

self.addEventListener('activate', function (e) {
  e.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', function (e) {
  var url = new URL(e.request.url);
  // El estado, la hoja y el chat nunca salen de lo guardado: siempre de la tablet.
  if (e.request.method !== 'GET' || url.pathname.indexOf('/api/') === 0) return;
  e.respondWith(fetch(e.request).then(function (res) {
    var copy = res.clone();
    caches.open(SHELL).then(function (c) { c.put(e.request, copy); });
    return res;
  }).catch(function () { return caches.match(e.request, { ignoreSearch: true }); }));
});
