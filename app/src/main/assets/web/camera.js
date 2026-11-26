// Trazo Side Chat: cámara y editor de fotos. Saca una foto (con la cámara del teléfono o, si el navegador
// deja, con la cámara dentro de la página), y la deja encuadrar: cuatro esquinas para enderezar un pizarrón
// o una hoja, girar y limpiar el fondo. Todo corre acá, en el navegador: no usa nada de afuera.
(function () {
  'use strict';

  var MAX_WORK = 2400;   // lado mayor de la copia con la que se trabaja
  var MAX_OUT = 2000;    // lado mayor de la foto que sale
  var HANDLE = 22;       // radio de las esquinas en la pantalla (px CSS)
  var PAD = 28;          // margen alrededor de la imagen para poder agarrar las esquinas

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  function button(label, cls, onClick) {
    var b = el('button', cls || 'pill', label);
    b.type = 'button';
    b.addEventListener('click', onClick);
    return b;
  }

  function canvas(w, h) {
    var c = document.createElement('canvas');
    c.width = Math.max(1, Math.round(w));
    c.height = Math.max(1, Math.round(h));
    return c;
  }

  /** La cámara dentro de la página: hace falta getUserMedia, y el navegador solo la da en un origen seguro. */
  function liveSupported() {
    return !!(window.isSecureContext && navigator.mediaDevices && navigator.mediaDevices.getUserMedia);
  }

  function liveProblem() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
      if (!window.isSecureContext) {
        return 'El navegador solo deja usar la cámara dentro de la página en conexiones seguras (https), y Trazo ' +
          'se abre por la red de casa sin https. Usá «Cámara del teléfono»: abre la app de cámara de siempre y la foto vuelve acá.';
      }
      return 'Este navegador no deja usar la cámara dentro de la página. Usá «Cámara del teléfono».';
    }
    return null;
  }

  // ---------------------------------------------------------------- cargar la imagen

  /** Un archivo, un canvas o un video → un canvas de trabajo (orientación corregida, lado ≤ MAX_WORK). */
  function toWorkCanvas(src) {
    if (src instanceof HTMLCanvasElement) return Promise.resolve(fit(src, src.width, src.height));
    return createImageBitmap(src, { imageOrientation: 'from-image' }).catch(function () {
      return createImageBitmap(src);
    }).then(function (bmp) {
      var c = fit(bmp, bmp.width, bmp.height);
      if (bmp.close) bmp.close();
      return c;
    });
  }

  function fit(img, w, h) {
    var k = Math.min(1, MAX_WORK / Math.max(w, h));
    var c = canvas(w * k, h * k);
    var g = c.getContext('2d');
    g.fillStyle = '#fff';
    g.fillRect(0, 0, c.width, c.height);
    g.drawImage(img, 0, 0, c.width, c.height);
    return c;
  }

  function rotate(src, clockwise) {
    var c = canvas(src.height, src.width);
    var g = c.getContext('2d');
    if (clockwise) { g.translate(c.width, 0); g.rotate(Math.PI / 2); } else { g.translate(0, c.height); g.rotate(-Math.PI / 2); }
    g.drawImage(src, 0, 0);
    return c;
  }

  // ---------------------------------------------------------------- perspectiva

  /** Homografía del cuadrado unidad a la cuadrilátera q = [x0,y0, x1,y1, x2,y2, x3,y3] (sup-izq, sup-der, inf-der, inf-izq). */
  function squareToQuad(q) {
    var x0 = q[0], y0 = q[1], x1 = q[2], y1 = q[3], x2 = q[4], y2 = q[5], x3 = q[6], y3 = q[7];
    var dx3 = x0 - x1 + x2 - x3, dy3 = y0 - y1 + y2 - y3;
    if (Math.abs(dx3) < 1e-9 && Math.abs(dy3) < 1e-9) {
      return [x1 - x0, x3 - x0, x0, y1 - y0, y3 - y0, y0, 0, 0];
    }
    var dx1 = x1 - x2, dx2 = x3 - x2, dy1 = y1 - y2, dy2 = y3 - y2;
    var den = dx1 * dy2 - dx2 * dy1;
    if (Math.abs(den) < 1e-12) return null;
    var g = (dx3 * dy2 - dx2 * dy3) / den;
    var h = (dx1 * dy3 - dx3 * dy1) / den;
    return [x1 - x0 + g * x1, x3 - x0 + h * x3, x0, y1 - y0 + g * y1, y3 - y0 + h * y3, y0, g, h];
  }

  function dist(ax, ay, bx, by) { return Math.hypot(bx - ax, by - ay); }

  /** Endereza la cuadrilátera q de src a un rectángulo (muestreo bilineal). */
  function warp(src, q) {
    var w = Math.max(dist(q[0], q[1], q[2], q[3]), dist(q[6], q[7], q[4], q[5]));
    var h = Math.max(dist(q[0], q[1], q[6], q[7]), dist(q[2], q[3], q[4], q[5]));
    var k = Math.min(1, MAX_OUT / Math.max(w, h, 1));
    var W = Math.max(1, Math.round(w * k)), H = Math.max(1, Math.round(h * k));
    var m = squareToQuad(q);
    var out = canvas(W, H);
    if (!m) return out;
    var sw = src.width, sh = src.height;
    var sd = src.getContext('2d').getImageData(0, 0, sw, sh).data;
    var og = out.getContext('2d');
    var img = og.createImageData(W, H);
    var od = img.data;
    var a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], gg = m[6], hh = m[7];
    var o = 0;
    for (var j = 0; j < H; j++) {
      var v = (j + 0.5) / H;
      for (var i = 0; i < W; i++) {
        var u = (i + 0.5) / W;
        var z = gg * u + hh * v + 1;
        var x = (a * u + b * v + c) / z - 0.5;
        var y = (d * u + e * v + f) / z - 0.5;
        var xi = Math.floor(x), yi = Math.floor(y);
        var fx = x - xi, fy = y - yi;
        if (xi < 0) { xi = 0; fx = 0; } else if (xi >= sw - 1) { xi = sw - 2; fx = 1; }
        if (yi < 0) { yi = 0; fy = 0; } else if (yi >= sh - 1) { yi = sh - 2; fy = 1; }
        if (sw < 2) { xi = 0; fx = 0; }
        if (sh < 2) { yi = 0; fy = 0; }
        var p00 = (yi * sw + xi) * 4;
        var p10 = p00 + (sw > 1 ? 4 : 0);
        var p01 = p00 + (sh > 1 ? sw * 4 : 0);
        var p11 = p01 + (sw > 1 ? 4 : 0);
        var w00 = (1 - fx) * (1 - fy), w10 = fx * (1 - fy), w01 = (1 - fx) * fy, w11 = fx * fy;
        od[o] = sd[p00] * w00 + sd[p10] * w10 + sd[p01] * w01 + sd[p11] * w11;
        od[o + 1] = sd[p00 + 1] * w00 + sd[p10 + 1] * w10 + sd[p01 + 1] * w01 + sd[p11 + 1] * w11;
        od[o + 2] = sd[p00 + 2] * w00 + sd[p10 + 2] * w10 + sd[p01 + 2] * w01 + sd[p11 + 2] * w11;
        od[o + 3] = 255;
        o += 4;
      }
    }
    og.putImageData(img, 0, 0);
    return out;
  }

  // ---------------------------------------------------------------- limpiar el fondo

  /** Una estimación suave del fondo (la luz sobre el pizarrón o la hoja), del tamaño de src. */
  function background(src) {
    var w = src.width, h = src.height;
    var sw = Math.max(4, Math.round(w / 32)), sh = Math.max(4, Math.round(h / 32));
    // Bajar en pasos para promediar bien.
    var cur = src, cw = w, ch = h;
    while (cw / 2 > sw && ch / 2 > sh) {
      cw = Math.round(cw / 2); ch = Math.round(ch / 2);
      var next = canvas(cw, ch);
      next.getContext('2d').drawImage(cur, 0, 0, cw, ch);
      cur = next;
    }
    var small = canvas(sw, sh);
    var sg = small.getContext('2d');
    sg.drawImage(cur, 0, 0, sw, sh);
    // Máximo 3×3: la tinta es más oscura que el fondo, así no lo ensucia.
    var id = sg.getImageData(0, 0, sw, sh);
    var s = id.data, t = new Uint8ClampedArray(s.length);
    for (var y = 0; y < sh; y++) {
      for (var x = 0; x < sw; x++) {
        for (var ch2 = 0; ch2 < 3; ch2++) {
          var best = 0;
          for (var dy = -1; dy <= 1; dy++) {
            var yy = Math.min(sh - 1, Math.max(0, y + dy));
            for (var dx = -1; dx <= 1; dx++) {
              var xx = Math.min(sw - 1, Math.max(0, x + dx));
              var val = s[(yy * sw + xx) * 4 + ch2];
              if (val > best) best = val;
            }
          }
          t[(y * sw + x) * 4 + ch2] = best;
        }
        t[(y * sw + x) * 4 + 3] = 255;
      }
    }
    id.data.set(t);
    sg.putImageData(id, 0, 0);
    var big = canvas(w, h);
    var bg = big.getContext('2d');
    bg.imageSmoothingEnabled = true;
    bg.imageSmoothingQuality = 'high';
    bg.drawImage(small, 0, 0, w, h);
    return bg.getImageData(0, 0, w, h).data;
  }

  /** «Pizarrón»: fondo parejo y blanco, colores de los marcadores más vivos. «Blanco y negro»: lo mismo en grises. */
  function clean(src, mode) {
    if (mode === 'original') return src;
    var w = src.width, h = src.height;
    var out = canvas(w, h);
    var g = out.getContext('2d');
    g.drawImage(src, 0, 0);
    var img = g.getImageData(0, 0, w, h);
    var d = img.data;
    var bg = background(src);
    var gray = mode === 'gris';
    for (var i = 0; i < d.length; i += 4) {
      var r = d[i] / Math.max(24, bg[i]);
      var gr = d[i + 1] / Math.max(24, bg[i + 1]);
      var b = d[i + 2] / Math.max(24, bg[i + 2]);
      if (gray) { var l = 0.3 * r + 0.59 * gr + 0.11 * b; r = gr = b = l; }
      d[i] = stretch(r);
      d[i + 1] = stretch(gr);
      d[i + 2] = stretch(b);
    }
    g.putImageData(img, 0, 0);
    return out;
  }

  function stretch(n) {
    var v = (n - 0.35) / 0.55;
    if (v >= 1) return 255;
    if (v <= 0) return 0;
    return Math.round(255 * Math.pow(v, 1.25));
  }

  // ---------------------------------------------------------------- el editor

  var openEditor = null;

  /**
   * Abre el editor con [src] (archivo o canvas). [actions]: [{label, primary, run(dataUrl) → Promise|void}].
   * [say] muestra avisos cortos.
   */
  function edit(src, actions, say) {
    say = say || function () {};
    return toWorkCanvas(src).then(function (work) { show(work, actions, say); }).catch(function () {
      say('Este navegador no pudo abrir esa foto. Probá con una JPG o PNG.');
    });
  }

  function show(work, actions, say) {
    if (openEditor) openEditor.close();
    var S = {
      src: work,
      quad: [0, 0, work.width, 0, work.width, work.height, 0, work.height],
      stage: 'crop',
      filter: 'pizarron',
      result: null,
      shown: null,
      drag: -1,
      scale: 1, ox: 0, oy: 0,
      busy: false,
    };

    var root = el('div', 'cam-editor');
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-modal', 'true');
    root.setAttribute('aria-label', 'Editar la foto');
    var head = el('div', 'cam-head');
    var title = el('div', 'cam-title', 'Encuadrar');
    var hint = el('div', 'cam-hint', 'Arrastrá las cuatro esquinas hasta los bordes del pizarrón o de la hoja.');
    var titles = el('div', 'cam-titles');
    titles.appendChild(title);
    titles.appendChild(hint);
    head.appendChild(titles);
    head.appendChild(button('Cancelar', 'pill', close));
    var stage = el('div', 'cam-stage');
    var view = el('canvas', 'cam-canvas');
    stage.appendChild(view);
    var tools = el('div', 'cam-tools');
    var bottom = el('div', 'cam-actions');
    root.appendChild(head);
    root.appendChild(stage);
    root.appendChild(tools);
    root.appendChild(bottom);
    document.body.appendChild(root);
    document.body.classList.add('cam-open');

    function close() {
      window.removeEventListener('resize', draw);
      document.removeEventListener('keydown', onKey);
      root.remove();
      document.body.classList.remove('cam-open');
      if (openEditor === api) openEditor = null;
    }
    var api = { close: close };
    openEditor = api;

    function onKey(e) { if (e.key === 'Escape') close(); }
    document.addEventListener('keydown', onKey);

    // ----- dibujo

    function draw() {
      var rect = stage.getBoundingClientRect();
      var dpr = window.devicePixelRatio || 1;
      var cw = Math.max(50, rect.width), ch = Math.max(50, rect.height);
      view.width = Math.round(cw * dpr);
      view.height = Math.round(ch * dpr);
      view.style.width = cw + 'px';
      view.style.height = ch + 'px';
      var g = view.getContext('2d');
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, cw, ch);
      var img = S.stage === 'crop' ? S.src : (S.shown || S.result);
      if (!img) return;
      var pad = S.stage === 'crop' ? PAD : 8;
      var k = Math.min((cw - 2 * pad) / img.width, (ch - 2 * pad) / img.height);
      S.scale = k;
      S.ox = (cw - img.width * k) / 2;
      S.oy = (ch - img.height * k) / 2;
      g.imageSmoothingQuality = 'high';
      g.drawImage(img, S.ox, S.oy, img.width * k, img.height * k);
      if (S.stage !== 'crop') return;
      var p = screenQuad();
      // Oscurece lo que queda afuera.
      g.save();
      g.fillStyle = 'rgba(0,0,0,.45)';
      g.beginPath();
      g.rect(0, 0, cw, ch);
      g.moveTo(p[0], p[1]); g.lineTo(p[6], p[7]); g.lineTo(p[4], p[5]); g.lineTo(p[2], p[3]); g.closePath();
      g.fill('evenodd');
      g.restore();
      g.strokeStyle = '#4fd1a5';
      g.lineWidth = 2;
      g.beginPath();
      g.moveTo(p[0], p[1]); g.lineTo(p[2], p[3]); g.lineTo(p[4], p[5]); g.lineTo(p[6], p[7]); g.closePath();
      g.stroke();
      for (var i = 0; i < 4; i++) {
        g.beginPath();
        g.arc(p[i * 2], p[i * 2 + 1], i === S.drag ? HANDLE : HANDLE * 0.7, 0, Math.PI * 2);
        g.fillStyle = 'rgba(79,209,165,.35)';
        g.fill();
        g.lineWidth = 2.5;
        g.strokeStyle = '#ffffff';
        g.stroke();
      }
      if (S.drag >= 0) loupe(g, p[S.drag * 2], p[S.drag * 2 + 1], cw);
    }

    /** Lupa en la esquina de arriba para ver dónde cae la esquina que se arrastra. */
    function loupe(g, sx, sy, cw) {
      var r = 54, zoom = 2.5;
      var cx = sx < cw / 2 ? cw - r - 12 : r + 12, cy = r + 12;
      var ix = (sx - S.ox) / S.scale, iy = (sy - S.oy) / S.scale;
      var half = r / (S.scale * zoom);
      g.save();
      g.beginPath();
      g.arc(cx, cy, r, 0, Math.PI * 2);
      g.clip();
      g.fillStyle = '#000';
      g.fillRect(cx - r, cy - r, 2 * r, 2 * r);
      g.drawImage(S.src, ix - half, iy - half, 2 * half, 2 * half, cx - r, cy - r, 2 * r, 2 * r);
      g.strokeStyle = '#4fd1a5';
      g.lineWidth = 1.5;
      g.beginPath(); g.moveTo(cx - 10, cy); g.lineTo(cx + 10, cy); g.moveTo(cx, cy - 10); g.lineTo(cx, cy + 10); g.stroke();
      g.restore();
      g.beginPath();
      g.arc(cx, cy, r, 0, Math.PI * 2);
      g.lineWidth = 2;
      g.strokeStyle = '#fff';
      g.stroke();
    }

    function screenQuad() {
      var q = S.quad, p = [];
      for (var i = 0; i < 8; i += 2) { p.push(S.ox + q[i] * S.scale, S.oy + q[i + 1] * S.scale); }
      return p;
    }

    // ----- esquinas

    function local(e) {
      var r = view.getBoundingClientRect();
      return [e.clientX - r.left, e.clientY - r.top];
    }

    view.addEventListener('pointerdown', function (e) {
      if (S.stage !== 'crop') return;
      var pt = local(e), p = screenQuad(), best = -1, bestD = HANDLE * 2.2;
      for (var i = 0; i < 4; i++) {
        var dd = dist(pt[0], pt[1], p[i * 2], p[i * 2 + 1]);
        if (dd < bestD) { bestD = dd; best = i; }
      }
      if (best < 0) return;
      e.preventDefault();
      S.drag = best;
      S.grab = [p[best * 2] - pt[0], p[best * 2 + 1] - pt[1]];
      view.setPointerCapture(e.pointerId);
      draw();
    });
    view.addEventListener('pointermove', function (e) {
      if (S.drag < 0) return;
      e.preventDefault();
      var pt = local(e);
      var x = (pt[0] + S.grab[0] - S.ox) / S.scale, y = (pt[1] + S.grab[1] - S.oy) / S.scale;
      S.quad[S.drag * 2] = Math.min(S.src.width, Math.max(0, x));
      S.quad[S.drag * 2 + 1] = Math.min(S.src.height, Math.max(0, y));
      draw();
    });
    function endDrag() { if (S.drag >= 0) { S.drag = -1; draw(); } }
    view.addEventListener('pointerup', endDrag);
    view.addEventListener('pointercancel', endDrag);

    /** Una cuadrilátera usable: convexa y no demasiado chica. */
    function quadOk(q) {
      var sign = 0;
      for (var i = 0; i < 4; i++) {
        var ax = q[i * 2], ay = q[i * 2 + 1];
        var bx = q[((i + 1) % 4) * 2], by = q[((i + 1) % 4) * 2 + 1];
        var cx = q[((i + 2) % 4) * 2], cy = q[((i + 2) % 4) * 2 + 1];
        var z = (bx - ax) * (cy - by) - (by - ay) * (cx - bx);
        if (Math.abs(z) < 1) return false;
        var s = z > 0 ? 1 : -1;
        if (sign === 0) sign = s; else if (s !== sign) return false;
      }
      return Math.min(dist(q[0], q[1], q[2], q[3]), dist(q[0], q[1], q[6], q[7])) > 16;
    }

    // ----- pasos

    function rotateSource(clockwise) {
      var w = S.src.width, h = S.src.height, q = S.quad, n = [];
      for (var i = 0; i < 8; i += 2) {
        var x = q[i], y = q[i + 1];
        if (clockwise) n.push(h - y, x); else n.push(y, w - x);
      }
      // Las esquinas siguen siendo sup-izq, sup-der, inf-der, inf-izq después de girar.
      S.quad = clockwise ? n.slice(6, 8).concat(n.slice(0, 6)) : n.slice(2, 8).concat(n.slice(0, 2));
      S.src = rotate(S.src, clockwise);
      draw();
    }

    function cropStage() {
      S.stage = 'crop';
      title.textContent = 'Encuadrar';
      hint.textContent = 'Arrastrá las cuatro esquinas hasta los bordes del pizarrón o de la hoja.';
      tools.textContent = '';
      tools.appendChild(button('⟲ Girar', 'pill', function () { rotateSource(false); }));
      tools.appendChild(button('⟳ Girar', 'pill', function () { rotateSource(true); }));
      tools.appendChild(button('Toda la foto', 'pill', function () {
        S.quad = [0, 0, S.src.width, 0, S.src.width, S.src.height, 0, S.src.height];
        draw();
      }));
      bottom.textContent = '';
      bottom.appendChild(button('Listo', 'pill primary', function () {
        if (!quadOk(S.quad)) { say('Las esquinas se cruzan o quedaron muy juntas. Acomodalas de nuevo.'); return; }
        working(true);
        setTimeout(function () {
          try {
            S.result = warp(S.src, S.quad);
            resultStage();
          } catch (err) {
            say('No se pudo enderezar la foto.');
          } finally { working(false); }
        }, 30);
      }));
      draw();
    }

    function working(on) {
      S.busy = on;
      root.classList.toggle('busy', on);
      var bs = root.querySelectorAll('button');
      for (var i = 0; i < bs.length; i++) bs[i].disabled = on;
    }

    function applyFilter() {
      try { S.shown = clean(S.result, S.filter); } catch (err) { S.shown = S.result; }
      draw();
    }

    function resultStage() {
      S.stage = 'result';
      title.textContent = 'Revisar la foto';
      hint.textContent = 'Elegí cómo se ve y mandala.';
      tools.textContent = '';
      var filters = [['original', 'Original'], ['pizarron', 'Pizarrón'], ['gris', 'Blanco y negro']];
      var chips = [];
      filters.forEach(function (f) {
        var b = button(f[1], 'choice', function () {
          S.filter = f[0];
          chips.forEach(function (c) { c.setAttribute('aria-pressed', String(c === b)); });
          applyFilter();
        });
        b.setAttribute('aria-pressed', String(S.filter === f[0]));
        chips.push(b);
        tools.appendChild(b);
      });
      tools.appendChild(button('⟳', 'pill', function () {
        S.result = rotate(S.result, true);
        applyFilter();
      }));
      bottom.textContent = '';
      bottom.appendChild(button('Volver a encuadrar', 'pill', cropStage));
      actions.forEach(function (a) {
        bottom.appendChild(button(a.label, a.primary ? 'pill primary' : 'pill', function () {
          var data = (S.shown || S.result).toDataURL('image/jpeg', 0.9);
          working(true);
          Promise.resolve(a.run(data)).then(function (ok) {
            working(false);
            if (ok !== false) close();
          }, function () { working(false); });
        }));
      });
      applyFilter();
    }

    window.addEventListener('resize', draw);
    cropStage();
  }

  // ---------------------------------------------------------------- la cámara en la página

  /** Abre la cámara dentro de la página; al sacar, llama a onShot(canvas). */
  function live(onShot, say) {
    say = say || function () {};
    var problem = liveProblem();
    if (problem) { say(problem); return Promise.resolve(false); }
    var facing = 'environment';
    var stream = null;
    var root = el('div', 'cam-live');
    root.setAttribute('role', 'dialog');
    root.setAttribute('aria-modal', 'true');
    root.setAttribute('aria-label', 'Cámara');
    var video = el('video', 'cam-video');
    video.setAttribute('playsinline', '');
    video.muted = true;
    video.autoplay = true;
    var bar = el('div', 'cam-live-bar');
    var closeBtn = button('Cerrar', 'pill', stop);
    var flip = button('Cambiar cámara', 'pill', function () {
      facing = facing === 'environment' ? 'user' : 'environment';
      start();
    });
    var shutter = el('button', 'cam-shutter');
    shutter.type = 'button';
    shutter.setAttribute('aria-label', 'Sacar la foto');
    shutter.addEventListener('click', function () {
      var w = video.videoWidth, h = video.videoHeight;
      if (!w || !h) { say('La cámara todavía no arrancó.'); return; }
      var c = canvas(w, h);
      c.getContext('2d').drawImage(video, 0, 0, w, h);
      stop();
      onShot(c);
    });
    bar.appendChild(closeBtn);
    bar.appendChild(shutter);
    bar.appendChild(flip);
    root.appendChild(video);
    root.appendChild(bar);
    document.body.appendChild(root);
    document.body.classList.add('cam-open');

    function release() {
      if (stream) stream.getTracks().forEach(function (t) { t.stop(); });
      stream = null;
    }
    function stop() {
      release();
      document.removeEventListener('keydown', onKey);
      root.remove();
      document.body.classList.remove('cam-open');
    }
    function onKey(e) { if (e.key === 'Escape') stop(); }
    document.addEventListener('keydown', onKey);

    function start() {
      release();
      return navigator.mediaDevices.getUserMedia({
        audio: false,
        video: { facingMode: { ideal: facing }, width: { ideal: 3840 }, height: { ideal: 2160 } },
      }).then(function (s) {
        if (!root.isConnected) { s.getTracks().forEach(function (t) { t.stop(); }); return; }
        stream = s;
        video.srcObject = s;
        return video.play().catch(function () {});
      }).catch(function (err) {
        stop();
        say(err && err.name === 'NotAllowedError'
          ? 'No hay permiso para la cámara. Habilitalo en el navegador o usá «Cámara del teléfono».'
          : 'No se pudo abrir la cámara. Usá «Cámara del teléfono».');
      });
    }
    return start().then(function () { return true; });
  }

  window.TrazoCamera = {
    liveSupported: liveSupported,
    liveProblem: liveProblem,
    live: live,
    edit: edit,
    // Para probar la geometría.
    _squareToQuad: squareToQuad,
  };
})();
