// La web de revisión de Trazo: muestra la hoja abierta en la tablet y su chat con el tutor, y deja mandar
// mensajes y fotos. Todo sale de la tablet (misma red o Tailscale); acá no hay ninguna dirección de afuera.
(function () {
  'use strict';

  var $ = function (id) { return document.getElementById(id); };
  var root = document.documentElement;
  var app = $('app');
  var chatPane = $('pane-chat');
  var pagePane = $('pane-page');
  var grip = $('grip');
  var rz = $('rz');
  var pgrip = $('pgrip');
  var prz = $('prz');

  var KEY_CHAT = 'trazo_chat_box';
  var KEY_PAGE = 'trazo_page_box';
  var KEY_DOCK = 'trazo_chat_dock';
  var KEY_TEXT = 'trazo_text';
  var KEY_LOOK = 'trazo_look';
  var KEY_INSTALL_DISMISSED = 'trazo_install_dismissed';

  var THEMES = [
    ['sistema', 'Sistema'], ['claro', 'Claro'], ['oscuro', 'Oscuro'], ['neon', 'Neón'],
    ['matrix', 'Matrix'], ['oled', 'OLED'], ['vintage', 'Vintage'],
    ['papel-viejo', 'Papel viejo'], ['papel-nuevo', 'Papel nuevo']
  ];
  var TEX = [
    ['', 'Automático'], ['#111111', 'Negro'], ['#1d4ed8', 'Azul'],
    ['#b91c1c', 'Rojo'], ['#15803d', 'Verde'], ['#7c3aed', 'Violeta']
  ];
  var VIEWS = [
    ['chat-libre', 'Chat libre'], ['ambos', 'Las dos'], ['hoja-libre', 'Hoja libre'],
    ['chat', 'Solo chat'], ['hoja', 'Solo hoja']
  ];
  var SIDES = [['right', 'Derecha'], ['left', 'Izquierda']];
  var PAPERS = [
    ['claro', 'Clara'], ['crema', 'Crema'], ['gris', 'Gris'],
    ['oscuro', 'Oscura'], ['negro', 'Negra']
  ];
  var VIEW_HELP = {
    'chat-libre': 'El chat flota libre sobre la hoja, que ocupa toda la pantalla.',
    'ambos': 'La hoja y el chat, uno al lado del otro. Arrastrá el borde del chat para cambiar su ancho.',
    'hoja-libre': 'La hoja flota libre sobre el chat, que ocupa toda la pantalla.',
    'chat': 'Solo el chat, a pantalla completa.',
    'hoja': 'Solo la hoja, a pantalla completa.'
  };

  var wide = window.matchMedia('(min-width: 900px)');

  function load(k) { try { return JSON.parse(localStorage.getItem(k)); } catch (e) { return null; } }
  function save(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* sin guardado */ } }
  function has(list, v) { return list.some(function (it) { return it[0] === v; }); }
  function assign(target, src) {
    if (!src) return target;
    for (var k in src) {
      if (Object.prototype.hasOwnProperty.call(src, k)) target[k] = src[k];
    }
    return target;
  }

  var saved = load(KEY_LOOK) || {};
  var look = { theme: saved.theme, tex: saved.tex, side: saved.side, view: saved.view, paper: saved.paper };
  if (!look.view) look.view = saved.layout === 'dock' ? 'ambos' : 'chat-libre';
  if (!has(THEMES, look.theme)) look.theme = 'sistema';
  if (!has(TEX, look.tex)) look.tex = '';
  if (!has(VIEWS, look.view)) look.view = 'chat-libre';
  if (!has(SIDES, look.side)) look.side = 'right';
  if (!has(PAPERS, look.paper)) look.paper = 'claro';

  var chatBox = load(KEY_CHAT) || {};
  var pageBox = load(KEY_PAGE) || {};
  var dock = load(KEY_DOCK) || {};
  var scale = Number(load(KEY_TEXT)) || 1;

  var S = {
    v: null,          // versión del estado que ya tenemos
    data: null,       // último estado
    online: true,
    fails: 0,
    timer: 0,
    ticking: false,
    sending: false,
    stick: false,     // bajar al final del chat en el próximo dibujo
    photo: null,      // foto lista para mandar (data URL)
    nodes: [],        // mensajes dibujados: {id, el}
    notesKey: '',
    quickKey: '',
    pageV: null,      // versión de la imagen que se ve
    pageWant: null,   // versión que se está por cargar
    pageAt: 0,
    pageTimer: 0,
    pageUrl: null,
    hashCode: null,
    pairing: false,   // se está vinculando con el código del QR
    install: null,
    toastTimer: 0,
    noPaper: false,   // la app no soporta el parámetro paper
    historySupported: null,
    viewingArchive: null
  };

  // ---------------------------------------------------------------- formato de fecha

  function when(at) {
    var d = new Date(at);
    var now = new Date();
    var pad = function (n) { return n < 10 ? '0' + n : '' + n; };
    var time = pad(d.getHours()) + ':' + pad(d.getMinutes());
    var today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
    var targetDay = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    var diffDays = Math.round((today - targetDay) / 86400000);
    if (diffDays === 0) return 'Hoy ' + time;
    if (diffDays === 1) return 'Ayer ' + time;
    var months = ['ene', 'feb', 'mar', 'abr', 'may', 'jun', 'jul', 'ago', 'sep', 'oct', 'nov', 'dic'];
    var str = d.getDate() + ' ' + months[d.getMonth()];
    if (d.getFullYear() !== now.getFullYear()) str += ' ' + d.getFullYear();
    return str + ' ' + time;
  }

  function span(cls, text) {
    var s = document.createElement('span');
    s.className = cls;
    s.textContent = text;
    return s;
  }

  // ---------------------------------------------------------------- pedidos

  function request(method, path, body, timeoutMs) {
    var ctl = new AbortController();
    var t = setTimeout(function () { ctl.abort(); }, timeoutMs || 8000);
    var post = method === 'POST';
    return fetch(path, {
      method: method,
      cache: 'no-store',
      credentials: 'same-origin',
      signal: ctl.signal,
      headers: post ? { 'Content-Type': 'application/json', 'X-Trazo': '1' } : undefined,
      body: post ? JSON.stringify(body || {}) : undefined,
    }).then(function (res) {
      return res.json().catch(function () { return null; }).then(function (json) {
        return { status: res.status, json: json };
      });
    }).finally(function () { clearTimeout(t); });
  }

  function delay() {
    if (document.hidden) return 20000;
    if (!S.online) return Math.min(1000 * Math.pow(2, S.fails), 6000);
    return S.data && S.data.busy ? 600 : 2000;
  }

  function tick() {
    clearTimeout(S.timer);
    if (S.ticking) return;
    S.ticking = true;
    request('GET', '/api/state' + (S.v ? '?have=' + encodeURIComponent(S.v) : '')).then(function (r) {
      if (r.status === 401) {
        S.v = null; S.data = null; S.fails = 0;
        setOnline(true);
        if (S.pairing) return;
        if (S.hashCode) { var code = S.hashCode; S.hashCode = null; pair(code, true); } else setView('pair');
        return;
      }
      if (r.status !== 200 || !r.json) throw new Error('estado ' + r.status);
      S.fails = 0;
      S.hashCode = null;
      setOnline(true);
      if (!r.json.same) applyState(r.json);
    }).catch(function (e) {
      if (window.console) console.warn('Trazo: no se pudo leer el estado', e);
      S.fails++;
      if (S.fails >= 2) setOnline(false);
    }).finally(function () {
      S.ticking = false;
      if (app.dataset.view !== 'pair') S.timer = setTimeout(tick, delay());
    });
  }

  // ---------------------------------------------------------------- vistas

  function setView(view) {
    if (app.dataset.view === view) return;
    app.dataset.view = view;
    closeMenu();
    if (view !== 'main') {
      $('dialog').hidden = true;
      $('history').hidden = true;
      if (S.viewingArchive) exitArchive();
    }
    if (view === 'pair' || view === 'loading') { $('title').textContent = 'Trazo'; document.title = 'Trazo Side Chat'; }
    if (view === 'pair') setTimeout(function () { $('code').focus(); }, 50);
    updateStatus();
    updateInstallButton();
  }

  function setOnline(on) {
    S.online = on;
    app.dataset.online = on ? '1' : '0';
    updateStatus();
    updateComposer();
    if (!on && app.dataset.view === 'loading') {
      $('loading-text').textContent = 'No se encuentra a Trazo. Abrilo en la tablet y dejá este equipo en la misma red.';
    }
  }

  function updateStatus() {
    var d = S.data;
    var text = 'Conectado';
    if (app.dataset.view === 'pair') text = 'Sin vincular';
    else if (app.dataset.view === 'loading') text = 'Conectando…';
    else if (!S.online) text = 'Sin conexión con Trazo · reintentando…';
    else if (d && d.open && d.busy) text = 'El tutor está escribiendo…';
    $('status').textContent = text;
  }

  function setTab(tab) {
    app.dataset.tab = tab;
    $('tab-chat').setAttribute('aria-pressed', tab === 'chat' ? 'true' : 'false');
    $('tab-page').setAttribute('aria-pressed', tab === 'page' ? 'true' : 'false');
    if (tab === 'chat') { $('tab-chat').classList.remove('new'); scrollDown(); }
  }

  function toast(text) {
    var t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(S.toastTimer);
    S.toastTimer = setTimeout(function () { t.hidden = true; }, 4500);
  }

  // ---------------------------------------------------------------- dibujar el estado

  function applyState(d) {
    d.messages = d.messages || [];
    d.notes = d.notes || [];
    d.quick = d.quick || [];
    S.v = d.v;
    S.data = d;
    if (!d.open) {
      $('closed-text').textContent = d.hint || 'Abrí una hoja en Trazo para verla acá.';
      $('title').textContent = 'Trazo';
      document.title = 'Trazo Side Chat';
      setView('closed');
      updateStatus();
      return;
    }
    setView('main');
    $('title').textContent = d.title || 'Trazo';
    document.title = d.title ? d.title + ' · Trazo Side Chat' : 'Trazo Side Chat';
    renderPage(d);
    renderNotes(d);
    if (!S.viewingArchive) {
      renderChat(d);
      renderQuick(d);
      updateComposer();
    }
    updateStatus();
    checkHistory();
  }

  function typeset(rootEl) {
    if (!window.katex) return;
    var list = rootEl.querySelectorAll('span.tex:not(.done)');
    for (var i = 0; i < list.length; i++) {
      var node = list[i];
      var src = node.textContent;
      try {
        window.katex.render(src, node, {
          displayMode: node.classList.contains('display'),
          throwOnError: false,
          strict: 'ignore',
          trust: false,
          output: 'htmlAndMathml',
          maxSize: 20,
        });
        node.classList.add('done');
      } catch (e) {
        node.textContent = src;
      }
    }
  }

  function rich(html, cls) {
    var el = document.createElement('div');
    el.className = cls || 'body';
    el.innerHTML = html;
    typeset(el);
    return el;
  }

  function messageNode(m) {
    var el = document.createElement('article');
    el.className = 'msg ' + (m.error ? 'err' : m.me ? 'me' : 'tutor');
    if (m.image) {
      var img = new Image();
      img.className = 'photo';
      img.alt = 'Foto adjunta';
      img.loading = 'lazy';
      img.src = '/api/image/' + m.image;
      el.appendChild(img);
    }
    if (m.html) el.appendChild(rich(m.html));
    if (m.correct === true || m.correct === false) {
      var v = document.createElement('div');
      v.className = 'verdict ' + (m.correct ? 'ok' : 'bad');
      var head = document.createElement('div');
      head.className = 'head';
      head.textContent = m.correct ? 'Correcto' : (m.line ? 'Primer error · renglón ' + m.line : 'Primer error');
      v.appendChild(head);
      if (m.verdict) v.appendChild(rich(m.verdict));
      el.appendChild(v);
    }
    return el;
  }

  function nearBottom() {
    var c = $('chat');
    return c.scrollHeight - c.scrollTop - c.clientHeight < 160;
  }

  function scrollDown() {
    var c = $('chat');
    c.scrollTop = c.scrollHeight;
  }

  function renderChat(d) {
    if (S.viewingArchive) return;
    var stick = S.stick || nearBottom();
    var box = $('msgs');
    var before = S.nodes.length;
    for (var i = 0; i < d.messages.length; i++) {
      var m = d.messages[i];
      var have = S.nodes[i];
      if (have && have.id === m.id) continue;
      var el = messageNode(m);
      if (have) { box.replaceChild(el, have.el); S.nodes[i] = { id: m.id, el: el }; }
      else { box.appendChild(el); S.nodes.push({ id: m.id, el: el }); }
    }
    while (S.nodes.length > d.messages.length) box.removeChild(S.nodes.pop().el);

    var stream = $('stream');
    stream.hidden = !d.busy;
    if (d.busy) {
      var body = stream.firstChild;
      body.innerHTML = d.streaming || '';
      typeset(body);
      $('typing-text').textContent = d.streaming ? 'Escribiendo…' : 'Revisando tu resolución…';
    }
    $('empty-chat').hidden = d.messages.length > 0 || d.busy;

    if (d.messages.length > before && app.dataset.tab !== 'chat') $('tab-chat').classList.add('new');
    if (stick) { scrollDown(); S.stick = false; }

    $('chat-head-title').textContent = 'Charla actual';
    $('chat-when').textContent = (d.messages.length && d.messages[0].at)
      ? ' · ' + when(d.messages[0].at).toLowerCase()
      : '';
  }

  function renderNotes(d) {
    var key = JSON.stringify(d.notes || []);
    if (key === S.notesKey) return;
    S.notesKey = key;
    var list = $('notes');
    list.textContent = '';
    (d.notes || []).forEach(function (n) {
      var li = document.createElement('li');
      var tag = document.createElement('div');
      tag.className = 'tag';
      tag.innerHTML = 'Renglón ' + Number(n.line) + (n.kind ? ' · ' + n.kind : '');
      li.appendChild(tag);
      if (n.short) li.appendChild(rich(n.short, 'short'));
      if (n.detail) li.appendChild(rich(n.detail, 'detail'));
      if (n.fix) li.appendChild(rich('<b>Arreglo:</b> ' + n.fix, 'fix'));
      list.appendChild(li);
    });
    $('notes-title').hidden = !(d.notes && d.notes.length);
  }

  function renderQuick(d) {
    if (S.viewingArchive) { $('quick').hidden = true; return; }
    var box = $('quick');
    var show = !d.busy && (d.quick || []).length > 0 && d.messages.some(function (m) { return !m.error && !m.me; });
    box.hidden = !show;
    var key = (d.quick || []).join('\n');
    if (key === S.quickKey) return;
    S.quickKey = key;
    box.textContent = '';
    (d.quick || []).forEach(function (q) {
      var b = document.createElement('button');
      b.type = 'button';
      b.textContent = q;
      b.addEventListener('click', function () { send(q); });
      box.appendChild(b);
    });
  }

  // ---------------------------------------------------------------- la hoja

  function pageUrl(v, omitPaper) {
    var url = '/api/page.png?v=' + encodeURIComponent(v);
    if (!omitPaper && !S.noPaper && look.paper) {
      url += '&paper=' + encodeURIComponent(look.paper);
    }
    return url;
  }

  function renderPage(d) {
    if (!d.page) {
      S.pageV = null; S.pageWant = null;
      clearTimeout(S.pageTimer);
      $('sheet').removeAttribute('src');
      $('sheet-empty').textContent = 'Esta hoja todavía no tiene nada para mostrar.';
      return;
    }
    if (d.page === S.pageV || d.page === S.pageWant) return;
    S.pageWant = d.page;
    clearTimeout(S.pageTimer);
    var wait = S.pageV ? Math.max(0, 2500 - (Date.now() - S.pageAt)) : 0;
    S.pageTimer = setTimeout(function () { loadPage(d.page); }, wait);
  }

  function loadPage(v) {
    S.pageAt = Date.now();
    var url = pageUrl(v);
    fetch(url, { cache: 'no-store', credentials: 'same-origin' }).then(function (res) {
      if (res.status === 400 && !S.noPaper) {
        S.noPaper = true;
        return fetch(pageUrl(v, true), { cache: 'no-store', credentials: 'same-origin' }).then(function (r2) {
          if (!r2.ok) throw new Error('hoja ' + r2.status);
          return r2.blob();
        });
      }
      if (!res.ok) throw new Error('hoja ' + res.status);
      return res.blob();
    }).then(function (blob) {
      var blobUrl = URL.createObjectURL(blob);
      var old = S.pageUrl;
      $('sheet').src = blobUrl;
      S.pageUrl = blobUrl;
      S.pageV = v;
      if (!$('viewer').hidden && $('viewer-img').dataset.sheet === '1') $('viewer-img').src = blobUrl;
      if (old) setTimeout(function () { URL.revokeObjectURL(old); }, 2000);
    }).catch(function () {
      setTimeout(function () {
        if (S.pageWant === v) { S.pageWant = null; if (S.data && S.data.open) renderPage(S.data); }
      }, 3000);
    });
  }

  function setPaper(id) {
    if (!has(PAPERS, id) || id === look.paper) return;
    setLook({ paper: id });
    S.pageV = null;
    if (S.data && S.data.page) loadPage(S.data.page);
  }

  function updateSwatches() {
    var swatches = document.querySelectorAll('.swatch');
    for (var i = 0; i < swatches.length; i++) {
      var b = swatches[i];
      b.setAttribute('aria-pressed', b.dataset.paper === look.paper ? 'true' : 'false');
    }
    var sheetBtn = $('sheet-btn');
    if (sheetBtn) sheetBtn.dataset.paper = look.paper;
  }

  function openViewer(src, isSheet) {
    if (!src) return;
    var img = $('viewer-img');
    img.src = src;
    img.dataset.sheet = isSheet ? '1' : '0';
    $('viewer').classList.remove('zoom');
    $('viewer').hidden = false;
  }

  function closeViewer() {
    $('viewer').hidden = true;
    $('viewer-img').removeAttribute('src');
  }

  // ---------------------------------------------------------------- historial

  function checkHistory() {
    if (!S.online || !S.data || !S.data.open || S.data.exam) {
      $('hist-btn').hidden = true;
      return;
    }
    request('GET', '/api/history').then(function (r) {
      if (r.status === 404) {
        S.historySupported = false;
        $('hist-btn').hidden = true;
      } else if (r.status === 200) {
        S.historySupported = true;
        if (!S.viewingArchive) $('hist-btn').hidden = false;
      }
    }).catch(function () {});
  }

  function historyRow(item, current) {
    var li = document.createElement('li');
    if (current) li.setAttribute('aria-current', 'true');
    var b = document.createElement('button');
    b.type = 'button';
    b.appendChild(span('h-when', when(item.at)));
    b.appendChild(span('h-count', item.count + (item.count === 1 ? ' mensaje' : ' mensajes')));
    b.appendChild(span('h-text', item.preview || ''));
    b.addEventListener('click', function () { openArchive(item.id); });
    li.appendChild(b);
    return li;
  }

  function openHistory() {
    if (S.historySupported === false) {
      toast('Esta versión de Trazo no tiene historial.');
      return;
    }
    $('history').hidden = false;
    request('GET', '/api/history').then(function (r) {
      if (r.status === 404) {
        S.historySupported = false;
        $('hist-btn').hidden = true;
        $('history').hidden = true;
        toast('Esta versión de Trazo no tiene historial.');
        return;
      }
      if (r.status !== 200 || !r.json) {
        toast('No se pudo leer el historial.');
        return;
      }
      var items = r.json.items || [];
      var list = $('hist-list');
      list.textContent = '';
      $('hist-empty').hidden = items.length > 0;
      items.forEach(function (it) {
        list.appendChild(historyRow(it, S.viewingArchive && S.viewingArchive.id === it.id));
      });
    }).catch(function () {
      toast('No se pudo hablar con Trazo. Probá de nuevo.');
    });
  }

  function closeHistory() {
    $('history').hidden = true;
  }

  function openArchive(id) {
    request('GET', '/api/history/' + encodeURIComponent(id)).then(function (r) {
      if (r.status === 404) {
        toast('Esa charla ya no existe.');
        openHistory();
        return;
      }
      if (r.status !== 200 || !r.json) {
        toast('No se pudo abrir esa charla.');
        return;
      }
      $('history').hidden = true;
      showArchive(r.json);
    }).catch(function () {
      toast('No se pudo hablar con Trazo. Probá de nuevo.');
    });
  }

  function showArchive(archive) {
    S.viewingArchive = archive;
    var box = $('msgs');
    box.textContent = '';
    S.nodes = [];
    (archive.messages || []).forEach(function (m) {
      var el = messageNode(m);
      box.appendChild(el);
      S.nodes.push({ id: m.id, el: el });
    });
    $('empty-chat').hidden = true;
    $('stream').hidden = true;
    $('quick').hidden = true;
    $('composer').hidden = true;
    $('archive-bar').hidden = false;
    $('archive-back').hidden = false;
    $('hist-btn').hidden = true;
    $('new-chat-btn').hidden = true;
    $('chat-head-title').textContent = 'Charla guardada';
    $('chat-when').textContent = archive.at ? ' · ' + when(archive.at).toLowerCase() : '';
    scrollDown();
  }

  function exitArchive() {
    S.viewingArchive = null;
    $('archive-bar').hidden = true;
    $('archive-back').hidden = true;
    $('composer').hidden = false;
    $('chat-head-title').textContent = 'Charla actual';
    if (S.historySupported !== false) $('hist-btn').hidden = false;
    $('new-chat-btn').hidden = false;
    if (S.data) {
      S.nodes = [];
      $('msgs').textContent = '';
      renderChat(S.data);
      renderQuick(S.data);
      updateComposer();
      $('chat-when').textContent = (S.data.messages && S.data.messages.length && S.data.messages[0].at)
        ? ' · ' + when(S.data.messages[0].at).toLowerCase()
        : '';
    }
  }

  function restoreArchive() {
    if (!S.viewingArchive) return;
    var id = S.viewingArchive.id;
    request('POST', '/api/history/restore', { id: id }).then(function (r) {
      if (r.status === 200 && r.json && r.json.ok) {
        exitArchive();
        toast('Se retomó la charla.');
        tick();
      } else {
        toast((r.json && r.json.error) || 'No se pudo retomar la charla.');
      }
    }).catch(function () {
      toast('No se pudo hablar con Trazo. Probá de nuevo.');
    });
  }

  function deleteArchive() {
    if (!S.viewingArchive) return;
    var id = S.viewingArchive.id;
    dialog('Borrar charla guardada', paragraph('Esta charla se borra del historial de esta hoja y no se puede recuperar.'), 'Borrar', function () {
      request('POST', '/api/history/delete', { id: id }).then(function (r) {
        if (r.status === 200 && r.json && r.json.ok) {
          exitArchive();
          toast('Charla borrada.');
          tick();
        } else {
          toast((r.json && r.json.error) || 'No se pudo borrar la charla.');
        }
      }).catch(function () {
        toast('No se pudo hablar con Trazo. Probá de nuevo.');
      });
    });
  }

  function askNewChat() {
    closeMenu();
    dialog(
      'Nueva charla',
      paragraph('La charla actual queda guardada en el historial y se empieza una nueva desde cero.'),
      'Nueva charla',
      function () {
        if (S.viewingArchive) exitArchive();
        review(true);
      }
    );
  }

  // ---------------------------------------------------------------- escribir y mandar

  function autosize() {
    var t = $('text');
    t.style.height = 'auto';
    t.style.height = Math.min(t.scrollHeight + 2, Math.round(window.innerHeight * 0.38)) + 'px';
  }

  function updateComposer() {
    var d = S.data;
    var can = S.online && !!d && d.open && !d.busy && !S.sending && !S.viewingArchive;
    $('send').disabled = !(can && ($('text').value.trim() || S.photo));
    var quick = $('quick').querySelectorAll('button');
    for (var i = 0; i < quick.length; i++) quick[i].disabled = !can;
    var reviewBtn = $('review-btn');
    if (reviewBtn) reviewBtn.disabled = !can;
  }

  function send(given) {
    var fromBox = given == null;
    var text = (fromBox ? $('text').value : given).trim();
    var d = S.data;
    if (S.sending || !d || !d.open || d.busy || (!text && !S.photo) || S.viewingArchive) return;
    S.sending = true;
    updateComposer();
    request('POST', '/api/chat', { text: text, image: S.photo || undefined }, 90000).then(function (r) {
      if (r.status === 401) { setView('pair'); return; }
      if (r.status !== 200) { toast((r.json && r.json.error) || 'No se pudo mandar el mensaje.'); return; }
      if (fromBox) { $('text').value = ''; autosize(); }
      setPhoto(null);
      S.stick = true;
      tick();
    }).catch(function () {
      toast('No se pudo hablar con Trazo. Probá de nuevo.');
    }).finally(function () {
      S.sending = false;
      updateComposer();
    });
  }

  function review(fresh) {
    request('POST', '/api/review', { fresh: !!fresh }, 15000).then(function (r) {
      if (r.status === 401) { setView('pair'); return; }
      if (r.status !== 200) { toast((r.json && r.json.error) || 'No se pudo pedir la revisión.'); return; }
      S.stick = true;
      tick();
    }).catch(function () { toast('No se pudo hablar con Trazo. Probá de nuevo.'); });
  }

  // ---------------------------------------------------------------- fotos

  function setPhoto(dataUrl) {
    S.photo = dataUrl;
    $('chip').hidden = !dataUrl;
    if (dataUrl) $('chip-img').src = dataUrl; else $('chip-img').removeAttribute('src');
    $('file').value = '';
    updateComposer();
  }

  function shrink(file) {
    return createImageBitmap(file, { imageOrientation: 'from-image' }).catch(function () {
      return createImageBitmap(file);
    }).then(function (bmp) {
      var max = 1800;
      var k = Math.min(1, max / Math.max(bmp.width, bmp.height));
      var c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(bmp.width * k));
      c.height = Math.max(1, Math.round(bmp.height * k));
      var g = c.getContext('2d');
      g.fillStyle = '#fff';
      g.fillRect(0, 0, c.width, c.height);
      g.drawImage(bmp, 0, 0, c.width, c.height);
      if (bmp.close) bmp.close();
      return c.toDataURL('image/jpeg', 0.88);
    });
  }

  function pickPhoto(file) {
    if (!file) return;
    if (file.type && file.type.indexOf('image/') !== 0) { toast('Ese archivo no es una imagen.'); return; }
    shrink(file).then(setPhoto).catch(function () {
      toast('Este navegador no pudo abrir esa imagen. Probá con una foto JPG o PNG.');
    });
  }

  // ---------------------------------------------------------------- vincular

  function pairError(text) { $('pair-error').textContent = text || ''; }

  function pair(code, fromLink) {
    code = String(code || '').replace(/\D/g, '');
    if (code.length !== 8) { setView('pair'); pairError('El código tiene 8 dígitos.'); return; }
    if (S.pairing) return;
    S.pairing = true;
    $('pair-btn').disabled = true;
    request('POST', '/api/pair', { code: code }).then(function (r) {
      S.pairing = false;
      if (r.status === 200 && r.json && r.json.ok) {
        $('code').value = '';
        pairError('');
        setView('loading');
        toast('Listo: este navegador quedó vinculado.');
        tick();
        return;
      }
      setView('pair');
      pairError((r.json && r.json.error) || 'No se pudo vincular.');
    }).catch(function () {
      S.pairing = false;
      setView('pair');
      pairError(fromLink ? 'No se pudo vincular con ese enlace. Escribí el código que muestra Trazo.' : 'No se pudo hablar con Trazo. Fijate que esté abierto y en la misma red.');
    }).finally(function () { $('pair-btn').disabled = false; });
  }

  // ---------------------------------------------------------------- menú y diálogos

  function closeMenu() { $('menu').hidden = true; }

  function modelUnpaired() {
    $('dialog').hidden = true;
    S.v = null; S.data = null;
    setView('pair');
    pairError('Este navegador ya no está vinculado. Escaneá el QR o escribí un código nuevo.');
  }

  function modelOpen() {
    return !$('dialog').hidden && $('dialog-title').textContent === 'Modelo de IA';
  }

  function loadModel(refresh) {
    request('GET', '/api/ai').then(function (r) {
      if (r.status === 401) return modelUnpaired();
      if (refresh && !modelOpen()) return;
      if (r.status === 200 && r.json && r.json.ok) showModel(r.json, !refresh);
      else toast((r.json && r.json.error) || 'No se pudo leer los modelos.');
    }).catch(function () { toast('No se pudo hablar con Trazo. Probá de nuevo.'); });
  }

  var modelFocus = null;

  function pickModel(payload, key) {
    modelFocus = key;
    request('POST', '/api/ai', payload).then(function (r) {
      if (r.status === 401) return modelUnpaired();
      if (r.status === 200) loadModel(true);
      else toast((r.json && r.json.error) || 'No se pudo cambiar el modelo.');
    }).catch(function () { toast('No se pudo hablar con Trazo. Probá de nuevo.'); });
  }

  function choiceButton(label, pressed, enabled, onPick, key) {
    var b = document.createElement('button');
    b.type = 'button';
    b.className = 'choice';
    b.textContent = label;
    b.dataset.k = key;
    b.setAttribute('aria-pressed', String(pressed));
    b.disabled = !enabled;
    b.addEventListener('click', onPick);
    return b;
  }

  function showModel(data, fresh) {
    var body = document.createElement('div');
    body.className = 'look';
    function part(title, node, note) {
      var s = document.createElement('div');
      s.className = 'look-part';
      var h = document.createElement('h3');
      h.textContent = title;
      s.appendChild(h);
      s.appendChild(node);
      if (note) s.appendChild(paragraph(note));
      return s;
    }
    var profiles = data.profiles || [];
    var models = document.createElement('div');
    models.className = 'choices';
    profiles.forEach(function (p) {
      models.appendChild(choiceButton(p.name + (p.model ? ' · ' + p.model : ''), data.current === p.id, p.ready, function () {
        pickModel({ profile: p.id }, 'p:' + p.id);
      }, 'p:' + p.id));
    });
    body.appendChild(part('Modelo', models, profiles.length
      ? 'Los apagados faltan configurar en Trazo (clave o inicio de sesión).'
      : 'Todavía no hay modelos configurados en Trazo.'));
    var quality = document.createElement('div');
    quality.className = 'choices';
    (data.qualities || []).forEach(function (q) {
      quality.appendChild(choiceButton(q.label, data.quality === q.key, !!data.current, function () { pickModel({ quality: q.key }, 'q:' + q.key); }, 'q:' + q.key));
    });
    body.appendChild(part('Calidad de la foto', quality, 'Una foto más grande lee mejor la letra, pero tarda más y gasta más.'));
    $('dialog-title').textContent = 'Modelo de IA';
    var box = $('dialog-body');
    box.textContent = '';
    box.appendChild(body);
    $('dialog-yes').textContent = 'Listo';
    $('dialog-yes').onclick = function () { $('dialog').hidden = true; };
    $('dialog-no').hidden = true;
    $('dialog').hidden = false;
    var again = modelFocus && box.querySelector('[data-k="' + modelFocus + '"]');
    modelFocus = null;
    if (!fresh && again && !again.disabled) again.focus(); else $('dialog-yes').focus();
  }

  function dialog(title, body, yes, onYes, noText, onNo) {
    $('dialog-title').textContent = title;
    var box = $('dialog-body');
    box.textContent = '';
    box.appendChild(body);
    var yesBtn = $('dialog-yes');
    var noBtn = $('dialog-no');
    yesBtn.textContent = yes || 'Entendido';
    noBtn.textContent = noText || 'Cancelar';
    noBtn.hidden = !onYes && !noText;
    yesBtn.onclick = function () { $('dialog').hidden = true; if (onYes) onYes(); };
    noBtn.onclick = function () { $('dialog').hidden = true; if (onNo) onNo(); };
    $('dialog').hidden = false;
    yesBtn.focus();
  }

  function paragraph(text) {
    var p = document.createElement('p');
    p.textContent = text;
    return p;
  }

  // ---------------------------------------------------------------- instalar

  function installStepText() {
    var ua = navigator.userAgent || '';
    var isIOS = /iPad|iPhone|iPod/.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
    if (isIOS) {
      return '1. En Safari, tocá el botón Compartir y elegí «Agregar a inicio».';
    }
    if (/Android/.test(ua)) {
      return '1. En Chrome, tocá el menú ⋮ y elegí «Agregar a la pantalla principal».';
    }
    return '1. En la computadora (Chrome o Edge), abrí el menú ⋮ y elegí «Transmitir, guardar y compartir» → «Instalar página como app».';
  }

  function installHelp(fromBar) {
    if (S.install) {
      var e = S.install;
      S.install = null;
      e.prompt();
      return;
    }
    var isStandalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
    var box = document.createElement('div');
    if (isStandalone) {
      box.appendChild(paragraph('Ya estás usando Trazo como app en este equipo.'));
      dialog('Instalar como app', box);
      updateInstallButton();
      return;
    }
    box.appendChild(paragraph('Queda un ícono de Trazo que entra directo, sin QR, mientras Trazo esté abierto en la tablet y en la misma red.'));
    var ol = document.createElement('ol');
    ol.className = 'steps';
    var li = document.createElement('li');
    li.textContent = installStepText().replace(/^\d+\.\s*/, '');
    ol.appendChild(li);
    box.appendChild(ol);

    if (fromBar) {
      dialog('Instalar como app', box, 'Entendido', null, 'Ahora no', function () {
        save(KEY_INSTALL_DISMISSED, 1);
        updateInstallButton();
      });
    } else {
      dialog('Instalar como app', box);
    }
  }

  function updateInstallButton() {
    var isStandalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
    var dismissed = !!load(KEY_INSTALL_DISMISSED);
    var btn = $('bar-install');
    if (btn) {
      btn.hidden = isStandalone || dismissed;
    }
  }

  // ---------------------------------------------------------------- arranque

  function build() {
    var chat = $('chat');
    var msgs = document.createElement('div');
    msgs.id = 'msgs';
    chat.appendChild(msgs);

    var stream = document.createElement('article');
    stream.id = 'stream';
    stream.className = 'msg tutor';
    stream.hidden = true;
    var body = document.createElement('div');
    body.className = 'body';
    stream.appendChild(body);
    var typing = document.createElement('div');
    typing.className = 'typing';
    var dots = document.createElement('span');
    dots.className = 'dots';
    dots.appendChild(document.createElement('i'));
    dots.appendChild(document.createElement('i'));
    dots.appendChild(document.createElement('i'));
    typing.appendChild(dots);
    var label = document.createElement('span');
    label.id = 'typing-text';
    typing.appendChild(label);
    stream.appendChild(typing);
    chat.appendChild(stream);

    var empty = document.createElement('div');
    empty.id = 'empty-chat';
    empty.hidden = true;
    empty.appendChild(paragraph('Todavía no hay conversación en esta hoja. El tutor puede rehacer tu resolución y decirte dónde está el primer error.'));
    var btn = document.createElement('button');
    btn.id = 'review-btn';
    btn.type = 'button';
    btn.className = 'primary pill';
    btn.textContent = 'Revisar esta hoja';
    btn.addEventListener('click', function () { review(false); });
    empty.appendChild(btn);
    chat.appendChild(empty);
  }

  function listen() {
    $('tab-chat').addEventListener('click', function () { setTab('chat'); });
    $('tab-page').addEventListener('click', function () { setTab('page'); });

    $('composer').addEventListener('submit', function (e) { e.preventDefault(); send(); });
    $('text').addEventListener('input', function () { autosize(); updateComposer(); });
    $('text').addEventListener('keydown', function (e) {
      if (e.key === 'Enter' && !e.shiftKey && !e.isComposing && !window.matchMedia('(pointer: coarse)').matches) {
        e.preventDefault();
        send();
      }
    });
    $('text').addEventListener('paste', function (e) {
      var items = (e.clipboardData && e.clipboardData.files) || [];
      if (items.length && items[0].type.indexOf('image/') === 0) { e.preventDefault(); pickPhoto(items[0]); }
    });
    $('attach').addEventListener('click', function () { $('file').click(); });
    $('file').addEventListener('change', function () { pickPhoto($('file').files[0]); });
    $('chip-x').addEventListener('click', function () { setPhoto(null); });
    var composer = $('composer');
    composer.addEventListener('dragover', function (e) { e.preventDefault(); composer.classList.add('drop'); });
    composer.addEventListener('dragleave', function () { composer.classList.remove('drop'); });
    composer.addEventListener('drop', function (e) {
      e.preventDefault();
      composer.classList.remove('drop');
      if (e.dataTransfer && e.dataTransfer.files.length) pickPhoto(e.dataTransfer.files[0]);
    });

    $('sheet-btn').addEventListener('click', function () { openViewer($('sheet').getAttribute('src'), true); });
    $('zoom-sheet').addEventListener('click', function () { openViewer($('sheet').getAttribute('src'), true); });
    $('msgs').addEventListener('click', function (e) {
      if (e.target && e.target.classList && e.target.classList.contains('photo')) openViewer(e.target.src, false);
    });
    $('viewer-x').addEventListener('click', closeViewer);
    $('viewer-img').addEventListener('click', function () { $('viewer').classList.toggle('zoom'); });

    $('menu-btn').addEventListener('click', function (e) {
      e.stopPropagation();
      $('m-review').hidden = app.dataset.view !== 'main';
      $('m-model').hidden = app.dataset.view !== 'main';
      $('menu').hidden = !$('menu').hidden;
    });
    document.addEventListener('click', function (e) { if (!$('menu').hidden && !$('menu').contains(e.target)) closeMenu(); });
    $('m-review').addEventListener('click', askNewChat);
    $('m-model').addEventListener('click', function () { closeMenu(); loadModel(); });
    $('m-install').addEventListener('click', function () { closeMenu(); installHelp(false); });
    $('m-unpair').addEventListener('click', function () {
      closeMenu();
      dialog('Desvincular este navegador', paragraph('Para volver a entrar vas a tener que escanear el QR o escribir un código nuevo.'), 'Desvincular', function () {
        request('POST', '/api/unpair', {}).then(function () { S.v = null; S.data = null; setView('pair'); pairError(''); }).catch(function () {
          toast('No se pudo hablar con Trazo. Probá de nuevo.');
        });
      });
    });

    $('bar-install').addEventListener('click', function () { installHelp(true); });
    $('hist-btn').addEventListener('click', openHistory);
    $('hist-back').addEventListener('click', closeHistory);
    $('new-chat-btn').addEventListener('click', askNewChat);
    $('archive-back').addEventListener('click', exitArchive);
    $('archive-restore').addEventListener('click', restoreArchive);
    $('archive-delete').addEventListener('click', deleteArchive);

    var swatches = document.querySelectorAll('.swatch');
    for (var i = 0; i < swatches.length; i++) {
      (function (btn) {
        btn.addEventListener('click', function () {
          setPaper(btn.dataset.paper);
        });
      })(swatches[i]);
    }

    $('pair-form').addEventListener('submit', function (e) { e.preventDefault(); pair($('code').value); });
    $('code').addEventListener('input', function () {
      var digits = $('code').value.replace(/\D/g, '').slice(0, 8);
      $('code').value = digits.length > 4 ? digits.slice(0, 4) + ' ' + digits.slice(4) : digits;
      pairError('');
      if (digits.length === 8) pair(digits);
    });

    document.addEventListener('keydown', function (e) {
      if (e.key !== 'Escape') return;
      if (!$('viewer').hidden) closeViewer();
      else if (!$('dialog').hidden) $('dialog').hidden = true;
      else if (!$('history').hidden) closeHistory();
      else if (S.viewingArchive) exitArchive();
      else closeMenu();
    });
    document.addEventListener('visibilitychange', function () { if (!document.hidden && app.dataset.view !== 'pair') tick(); });
    window.addEventListener('online', function () { if (app.dataset.view !== 'pair') tick(); });
    window.addEventListener('beforeinstallprompt', function (e) {
      e.preventDefault();
      S.install = e;
      var isStandalone = window.matchMedia('(display-mode: standalone)').matches || window.navigator.standalone === true;
      if (!isStandalone) $('bar-install').hidden = false;
    });

    grip.addEventListener('pointerdown', function (e) { startDrag(e, chatPane, 'move'); });
    rz.addEventListener('pointerdown', function (e) { startDrag(e, chatPane, 'size'); });
    pgrip.addEventListener('pointerdown', function (e) { startDrag(e, pagePane, 'move'); });
    prz.addEventListener('pointerdown', function (e) { startDrag(e, pagePane, 'size'); });
    $('fs-down').addEventListener('click', function () { setText(scale - 0.1); });
    $('fs-up').addEventListener('click', function () { setText(scale + 0.1); });
    $('m-look').addEventListener('click', function () {
      closeMenu();
      renderLook();
    });
    window.addEventListener('resize', place);
    if (wide.addEventListener) wide.addEventListener('change', place);
  }

  function codeFromLink() {
    var m = /[#&]c=(\d{8})\b/.exec(location.hash || '');
    if (location.hash) { try { history.replaceState(null, '', location.pathname + location.search); } catch (e) { /* sin historial */ } }
    return m ? m[1] : null;
  }

  // ---------------------------------------------------------------- apariencia y vistas en PC

  function applyLook() {
    if (look.theme === 'sistema') root.removeAttribute('data-theme'); else root.setAttribute('data-theme', look.theme);
    if (look.tex) root.style.setProperty('--tex', look.tex); else root.style.removeProperty('--tex');
    app.setAttribute('data-side', look.side);
  }

  function applyText() { app.style.fontSize = (16 * scale).toFixed(1) + 'px'; }

  function setText(next) {
    scale = Math.min(1.6, Math.max(0.8, Math.round(next * 10) / 10));
    save(KEY_TEXT, scale);
    applyText();
  }

  function setLook(partial) {
    look = assign(assign({}, look), partial);
    save(KEY_LOOK, look);
    applyLook();
    updateSwatches();
    place();
  }

  function clampBox(b) {
    var vw = window.innerWidth, vh = window.innerHeight;
    b.w = Math.max(280, Math.min(b.w, vw - 8));
    b.h = Math.max(220, Math.min(b.h, vh - 8));
    b.y = Math.max(4, Math.min(b.y, vh - b.h - 4));
    b.x = Math.max(4, Math.min(b.x, vw - b.w - 4));
    return b;
  }

  function floatDefaults(which, b) {
    var vw = window.innerWidth, vh = window.innerHeight;
    if (typeof b.w !== 'number') b.w = which === 'chat' ? 440 : Math.min(760, Math.round(vw * 0.5));
    if (typeof b.h !== 'number') b.h = vh - 100;
    if (typeof b.y !== 'number') b.y = 72;
    if (typeof b.x !== 'number') {
      var chatLeft = look.side === 'left';
      var left = which === 'chat' ? chatLeft : !chatLeft;
      b.x = left ? 12 : vw - b.w - 12;
    }
    return b;
  }

  function applyPaneMode(el, mode, which, b) {
    el.classList.remove('float', 'docked', 'full', 'off');
    el.removeAttribute('style');
    if (mode === 'none') return;
    if (mode === 'off') { el.classList.add('off'); return; }
    if (mode === 'full') { el.classList.add('full'); return; }
    if (mode === 'float') {
      el.classList.add('float');
      floatDefaults(which, b);
      clampBox(b);
      el.style.left = b.x + 'px';
      el.style.top = b.y + 'px';
      el.style.width = b.w + 'px';
      el.style.height = b.h + 'px';
      return;
    }
    if (mode === 'dock') {
      el.classList.add('docked');
      if (typeof dock.w !== 'number') dock.w = 420;
      dock.w = Math.max(300, Math.min(dock.w, Math.round(window.innerWidth * 0.6)));
      el.style.width = dock.w + 'px';
    }
  }

  function place() {
    var modes;
    if (!wide.matches) modes = { chat: 'none', page: 'none' };
    else if (look.view === 'chat-libre') modes = { chat: 'float', page: 'full' };
    else if (look.view === 'ambos') modes = { chat: 'dock', page: 'full' };
    else if (look.view === 'hoja-libre') modes = { chat: 'full', page: 'float' };
    else if (look.view === 'chat') modes = { chat: 'full', page: 'off' };
    else modes = { chat: 'off', page: 'full' };
    applyPaneMode(chatPane, modes.chat, 'chat', chatBox);
    applyPaneMode(pagePane, modes.page, 'page', pageBox);
  }

  function startDrag(e, pane, kind) {
    if (e.button !== undefined && e.button !== 0) return;
    if (e.target.closest && e.target.closest('button')) return;
    var docked = pane.classList.contains('docked');
    var floating = pane.classList.contains('float');
    if (!docked && !floating) return;
    if (kind === 'move' && docked) return;
    var isChat = pane === chatPane;
    var b = isChat ? chatBox : pageBox;
    var key = isChat ? KEY_CHAT : KEY_PAGE;
    e.preventDefault();
    var target = e.currentTarget;
    var sx = e.clientX, sy = e.clientY;
    var start = { x: b.x, y: b.y, w: b.w, h: b.h, dw: dock.w };
    target.setPointerCapture(e.pointerId);
    function move(ev) {
      var dx = ev.clientX - sx, dy = ev.clientY - sy;
      if (docked) dock.w = start.dw + (look.side === 'left' ? dx : -dx);
      else if (kind === 'move') { b.x = start.x + dx; b.y = start.y + dy; }
      else { b.w = start.w + dx; b.h = Math.min(start.h + dy, window.innerHeight - b.y - 4); }
      place();
    }
    function up() {
      target.removeEventListener('pointermove', move);
      target.removeEventListener('pointerup', up);
      target.removeEventListener('pointercancel', up);
      if (docked) save(KEY_DOCK, dock); else save(key, b);
    }
    target.addEventListener('pointermove', move);
    target.addEventListener('pointerup', up);
    target.addEventListener('pointercancel', up);
  }

  function el(tag, cls, text) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    if (text != null) n.textContent = text;
    return n;
  }

  function choices(list, current, pick) {
    var wrap = el('div', 'choices');
    list.forEach(function (it) {
      var b = el('button', 'choice', it[1]);
      b.type = 'button';
      b.setAttribute('aria-pressed', String(current === it[0]));
      b.addEventListener('click', function () { pick(it[0]); renderLook(); });
      wrap.appendChild(b);
    });
    return wrap;
  }

  function section(title, node) {
    var s = el('div', 'look-part');
    s.appendChild(el('h3', null, title));
    s.appendChild(node);
    return s;
  }

  function renderLook() {
    var body = el('div', 'look');
    body.appendChild(section('Tema', choices(THEMES, look.theme, function (v) { setLook({ theme: v }); })));
    body.appendChild(section('Color de las fórmulas', choices(TEX, look.tex, function (v) { setLook({ tex: v }); })));
    body.appendChild(section('Vista en PC', choices(VIEWS, look.view, function (v) { setLook({ view: v }); })));
    body.appendChild(el('p', 'fine', VIEW_HELP[look.view]));
    body.appendChild(section('Lado del chat', choices(SIDES, look.side, function (v) {
      delete chatBox.x; delete pageBox.x;
      save(KEY_CHAT, chatBox); save(KEY_PAGE, pageBox);
      setLook({ side: v });
    })));
    body.appendChild(el('p', 'fine', 'En el teléfono no cambia nada. Las vistas libres se mueven arrastrando su barra y se agrandan desde la esquina.'));
    $('dialog-title').textContent = 'Apariencia';
    var box = $('dialog-body');
    box.textContent = '';
    box.appendChild(body);
    var yes = $('dialog-yes');
    yes.textContent = 'Listo';
    yes.onclick = function () { $('dialog').hidden = true; };
    $('dialog-no').hidden = true;
    $('dialog').hidden = false;
    yes.focus();
  }

  // ---------------------------------------------------------------- inicio

  S.hashCode = codeFromLink();
  window.addEventListener('hashchange', function () {
    var code = codeFromLink();
    if (code && app.dataset.view === 'pair') pair(code, true);
  });

  if ('serviceWorker' in navigator && window.isSecureContext) {
    navigator.serviceWorker.register('/sw.js').catch(function () {});
  }

  build();
  listen();
  applyLook();
  applyText();
  updateSwatches();
  updateInstallButton();
  setTab('chat');
  place();
  tick();
})();
