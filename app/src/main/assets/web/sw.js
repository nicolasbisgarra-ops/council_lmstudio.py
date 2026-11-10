// Trazo Side Chat: el service worker existe para que Chrome ofrezca instalar la app. No guarda nada: cada
// pedido va a Trazo, así que la app siempre muestra la hoja actual.
self.addEventListener('install', function () { self.skipWaiting(); });
self.addEventListener('activate', function (e) { e.waitUntil(self.clients.claim()); });
self.addEventListener('fetch', function (e) {
  if (e.request.method === 'GET') e.respondWith(fetch(e.request));
});
