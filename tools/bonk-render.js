/*
 * bonk-render.js - draws Bonk characters as soft 3D figures. No dependencies.
 * Used by the Character Studio page (browser) and tools/render.js (Node).
 *
 * Every part is a shaded ellipsoid or capsule with real per-pixel normals, a key
 * light from the top-left, a specular highlight and a gentle rim light, rendered
 * supersampled for smooth edges. All coordinates live in a 260 x 240 design box.
 */
(function (root) {
  "use strict";

  var BOX_W = 260, BOX_H = 240;
  var L = (function () { var v = [-0.45, -0.62, 0.64], n = Math.hypot(v[0], v[1], v[2]); return [v[0] / n, v[1] / n, v[2] / n]; })();

  // ------------------------------------------------------------- colours
  function hex(h) { h = String(h).replace("#", ""); return [parseInt(h.substr(0, 2), 16), parseInt(h.substr(2, 2), 16), parseInt(h.substr(4, 2), 16)]; }
  function mix(a, b, t) { return [a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t]; }
  /** A lit/shadow pair from one colour; shadows lean cool so whites look soft, not grey. */
  function pal(h) { var c = hex(h); return [mix(c, [255, 255, 255], 0.1), mix(c, [24, 24, 48], 0.42)]; }

  // ------------------------------------------------------------- species & options
  var SPECIES = {
    panda:  { label: "Panda",      fur: "#ffffff", accent: "#2e2e38", ears: "round",  limbs: "accent", patches: true },
    bear:   { label: "Brown bear", fur: "#a8744c", accent: "#6e4628", ears: "round",  limbs: "accent", muzzle: "#f3e0c6" },
    polar:  { label: "Polar bear", fur: "#ffffff", accent: "#ffffff", ears: "round",  limbs: "fur",    muzzle: "#fffaf2", inner: "#ffc4d6" },
    cat:    { label: "Cat",        fur: "#a3a8b6", accent: "#6c7280", ears: "pointy", limbs: "fur",    muzzle: "#eef0f4", inner: "#ffc4d6", whiskers: true },
    fox:    { label: "Fox",        fur: "#f0883a", accent: "#3a2a2a", ears: "pointy", limbs: "accent", muzzle: "#fff6ec", inner: "#fff6ec", cheeks: true },
    bunny:  { label: "Bunny",      fur: "#fff4ea", accent: "#fff4ea", ears: "long",   limbs: "fur",    muzzle: "#ffffff", inner: "#ffc4d6" },
    puppy:  { label: "Puppy",      fur: "#f2d3a6", accent: "#a8743f", ears: "floppy", limbs: "fur",    muzzle: "#fff3e0" },
    human:  { label: "Human",      fur: "#e8bd9c", accent: "#3a3340", ears: "human",  limbs: "fur",    human: true }
  };
  var OPTIONS = {
    species: Object.keys(SPECIES),
    outfit: ["none", "trench", "suit", "hoodie", "cardigan"],
    hat: ["none", "fedora", "beanie", "bow"],
    eyewear: ["none", "shades", "glasses"],
    expression: ["cheery", "smug", "deadpan"],
    hair: ["short", "bob", "long", "curly", "bun", "buzz"]
  };
  /** Skin tones offered for human characters. */
  var SKINS = ["#f6d9c6", "#e8bd9c", "#d29b72", "#b07850", "#87563a", "#5e3826"];
  var DEFAULTS = {
    name: "Bonk", species: "panda", fur: null, accent: null,
    outfit: "none", outfit_color: "#d6b276", tie_color: "#d64550",
    hat: "none", hat_color: "#5c5860", eyewear: "none", expression: "cheery",
    personality: "cheerleader", hair: "short", hair_color: "#3b2a22"
  };

  function resolve(spec) {
    var s = {}, k;
    for (k in DEFAULTS) s[k] = DEFAULTS[k];
    for (k in spec) if (spec[k] !== undefined && spec[k] !== null && spec[k] !== "") s[k] = spec[k];
    var sp = SPECIES[s.species] || SPECIES.panda;
    s.sp = sp;
    s.fur = s.fur || sp.fur;
    s.accent = s.accent || sp.accent;
    return s;
  }

  // ------------------------------------------------------------- canvas
  function Canvas(scale, ss) {
    this.k = scale * ss;
    this.w = Math.round(BOX_W * this.k);
    this.h = Math.round(BOX_H * this.k);
    this.rgb = new Float32Array(this.w * this.h * 3);   // premultiplied
    this.a = new Float32Array(this.w * this.h);
    this.tmp = [0, 0, 0];
  }

  Canvas.prototype._fill = function (x0, y0, x1, y1, fn) {
    var k = this.k, w = this.w;
    var X0 = Math.max(0, Math.floor(x0 * k)), Y0 = Math.max(0, Math.floor(y0 * k));
    var X1 = Math.min(this.w - 1, Math.ceil(x1 * k)), Y1 = Math.min(this.h - 1, Math.ceil(y1 * k));
    var out = this.tmp, rgb = this.rgb, A = this.a;
    for (var Y = Y0; Y <= Y1; Y++) {
      var y = (Y + 0.5) / k;
      for (var X = X0; X <= X1; X++) {
        var a = fn((X + 0.5) / k, y, out);
        if (!(a > 0)) continue;
        var i = Y * w + X, i3 = i * 3;
        rgb[i3] = out[0] * a + rgb[i3] * (1 - a);
        rgb[i3 + 1] = out[1] * a + rgb[i3 + 1] * (1 - a);
        rgb[i3 + 2] = out[2] * a + rgb[i3 + 2] * (1 - a);
        A[i] = a + A[i] * (1 - a);
      }
    }
  };

  function shadeInto(out, nx, ny, lit, dark, spec, gloss) {
    nx *= 0.95; ny *= 0.95;
    var nz = Math.sqrt(Math.max(0, 1 - nx * nx - ny * ny));
    var ndl = nx * L[0] + ny * L[1] + nz * L[2];
    var diff = Math.pow(Math.min(1, Math.max(0, ndl * 0.5 + 0.5)), 1.3);
    var rz = 2 * ndl * nz - L[2];
    var s = Math.pow(Math.min(1, Math.max(0, rz)), gloss) * 255 * spec;
    var rim = Math.pow(Math.max(0, 1 - nz), 3) * 36;
    for (var c = 0; c < 3; c++) out[c] = Math.min(255, dark[c] + (lit[c] - dark[c]) * diff + s + rim);
  }

  Canvas.prototype.ellipsoid = function (cx, cy, rx, ry, col, o) {
    o = o || {};
    var ang = (o.angle || 0) * Math.PI / 180, ca = Math.cos(ang), sa = Math.sin(ang);
    var spec = o.spec === undefined ? 0.3 : o.spec, gloss = o.gloss || 18, r = Math.max(rx, ry);
    var lit = col[0], dark = col[1], maxY = o.maxY === undefined ? Infinity : o.maxY;
    this._fill(cx - r, cy - r, cx + r, Math.min(cy + r, maxY), function (x, y, out) {
      if (y > maxY) return 0;
      var dx = x - cx, dy = y - cy;
      var lx = (dx * ca + dy * sa) / rx, ly = (-dx * sa + dy * ca) / ry;
      if (lx * lx + ly * ly > 1) return 0;
      shadeInto(out, lx * ca - ly * sa, lx * sa + ly * ca, lit, dark, spec, gloss);
      return 1;
    });
  };

  Canvas.prototype.capsule = function (x1, y1, x2, y2, r, col, o) {
    o = o || {};
    var spec = o.spec === undefined ? 0.28 : o.spec, gloss = o.gloss || 16;
    var vx = x2 - x1, vy = y2 - y1, L2 = vx * vx + vy * vy || 1e-6, lit = col[0], dark = col[1];
    this._fill(Math.min(x1, x2) - r, Math.min(y1, y2) - r, Math.max(x1, x2) + r, Math.max(y1, y2) + r, function (x, y, out) {
      var t = Math.min(1, Math.max(0, ((x - x1) * vx + (y - y1) * vy) / L2));
      var nx = (x - (x1 + t * vx)) / r, ny = (y - (y1 + t * vy)) / r;
      if (nx * nx + ny * ny > 1) return 0;
      shadeInto(out, nx, ny, lit, dark, spec, gloss);
      return 1;
    });
  };

  /** Soft-edged flat blob (blush, shadows, lens tint). */
  Canvas.prototype.soft = function (cx, cy, rx, ry, rgb, opacity) {
    this._fill(cx - rx, cy - ry, cx + rx, cy + ry, function (x, y, out) {
      var d = ((x - cx) / rx) * ((x - cx) / rx) + ((y - cy) / ry) * ((y - cy) / ry);
      if (d >= 1) return 0;
      out[0] = rgb[0]; out[1] = rgb[1]; out[2] = rgb[2];
      return Math.pow(1 - d, 1.2) * opacity;
    });
  };

  Canvas.prototype.disc = function (cx, cy, r, rgb, opacity) {
    var op = opacity === undefined ? 1 : opacity;
    this._fill(cx - r, cy - r, cx + r, cy + r, function (x, y, out) {
      if ((x - cx) * (x - cx) + (y - cy) * (y - cy) > r * r) return 0;
      out[0] = rgb[0]; out[1] = rgb[1]; out[2] = rgb[2];
      return op;
    });
  };

  /** Flat line with round caps. */
  Canvas.prototype.line = function (x1, y1, x2, y2, width, rgb, opacity) {
    var r = width / 2, op = opacity === undefined ? 1 : opacity;
    var vx = x2 - x1, vy = y2 - y1, L2 = vx * vx + vy * vy || 1e-6;
    this._fill(Math.min(x1, x2) - r, Math.min(y1, y2) - r, Math.max(x1, x2) + r, Math.max(y1, y2) + r, function (x, y, out) {
      var t = Math.min(1, Math.max(0, ((x - x1) * vx + (y - y1) * vy) / L2));
      var dx = x - (x1 + t * vx), dy = y - (y1 + t * vy);
      if (dx * dx + dy * dy > r * r) return 0;
      out[0] = rgb[0]; out[1] = rgb[1]; out[2] = rgb[2];
      return op;
    });
  };

  /** Elliptical arc; angles in degrees, clockwise from 3 o'clock (screen y points down). */
  Canvas.prototype.arc = function (cx, cy, rx, ry, a0, a1, width, rgb) {
    var r = width / 2, m = Math.min(rx, ry);
    this._fill(cx - rx - r, cy - ry - r, cx + rx + r, cy + ry + r, function (x, y, out) {
      var ux = (x - cx) / rx, uy = (y - cy) / ry;
      var ang = Math.atan2(uy, ux) * 180 / Math.PI; if (ang < 0) ang += 360;
      var inRange = a0 <= a1 ? (ang >= a0 && ang <= a1) : (ang >= a0 || ang <= a1);
      if (!inRange) return 0;
      if (Math.abs(Math.sqrt(ux * ux + uy * uy) - 1) * m > r) return 0;
      out[0] = rgb[0]; out[1] = rgb[1]; out[2] = rgb[2];
      return 1;
    });
  };

  /** Downsample to RGBA bytes (straight alpha). */
  Canvas.prototype.toRGBA = function (ss) {
    var W = Math.floor(this.w / ss), H = Math.floor(this.h / ss), out = new Uint8ClampedArray(W * H * 4), n = ss * ss;
    for (var Y = 0; Y < H; Y++) for (var X = 0; X < W; X++) {
      var r = 0, g = 0, b = 0, a = 0;
      for (var j = 0; j < ss; j++) for (var i = 0; i < ss; i++) {
        var p = (Y * ss + j) * this.w + X * ss + i;
        r += this.rgb[p * 3]; g += this.rgb[p * 3 + 1]; b += this.rgb[p * 3 + 2]; a += this.a[p];
      }
      var o = (Y * W + X) * 4;
      if (a > 0) { out[o] = r / a; out[o + 1] = g / a; out[o + 2] = b / a; }
      out[o + 3] = a / n * 255;
    }
    return { width: W, height: H, data: out };
  };

  // ------------------------------------------------------------- the character
  var INK = [36, 30, 40], WHITE = [255, 255, 255], PINK = [255, 140, 180];
  var EYE = [[92, 64, 58], [6, 4, 4]];
  var LENS = [[78, 88, 112], [4, 5, 9]];

  function drawCharacter(c, spec, pose) {
    var s = resolve(spec), sp = s.sp;
    pose = pose || {};
    var swing = pose.swing || 0, y = Math.abs(swing) * 3;
    var FUR = pal(s.fur), ACC = pal(s.accent), LIMB = sp.limbs === "accent" ? ACC : FUR;
    var dressed = s.outfit !== "none", CLOTH = pal(s.outfit_color), HATC = pal(s.hat_color);
    var sleeved = dressed || !!sp.human;                  // humans wear at least a tee
    var LEGS = sp.human ? pal("#3a3340") : LIMB;
    var lighter = function (h, t) { var v = mix(hex(h), [255, 255, 255], t); return [mix(v, [255, 255, 255], 0.1), mix(v, [24, 24, 48], 0.42)]; };
    var darker = function (h, t) { var v = mix(hex(h), [0, 0, 0], t); return [mix(v, [255, 255, 255], 0.1), mix(v, [24, 24, 48], 0.42)]; };

    // ground shadow
    c.soft(130, 226, 40 - y, 7, [40, 34, 70], 0.26);

    // legs
    [[117, swing], [143, -swing]].forEach(function (l) { c.ellipsoid(l[0], 210 + y + 5 * l[1], 12, 12, LEGS); });

    // long hair falls behind the shoulders
    if (sp.human && s.hair === "long") c.ellipsoid(130, 150 + y, 50, 56, pal(s.hair_color), { maxY: 192 + y, spec: 0.3 });

    // hood sits behind the head
    if (s.outfit === "hoodie") c.ellipsoid(130, 146 + y, 44, 20, CLOTH, { spec: 0.15 });

    // body + outfit
    if (!dressed && sp.human) {
      c.ellipsoid(130, 178 + y, 32, 33, CLOTH, { spec: 0.2 });                  // plain tee
      c.ellipsoid(130, 148 + y, 9, 6, FUR, { spec: 0.15 });
    } else if (!dressed) {
      c.ellipsoid(130, 178 + y, 31, 32, FUR, { spec: 0.22 });
      c.ellipsoid(130, 183 + y, 17, 19, lighter(s.fur, 0.55), { spec: 0.1 });
    } else {
      c.ellipsoid(130, 178 + y, 32, 33, CLOTH, { spec: 0.2 });
      if (s.outfit === "trench") {
        c.line(131, 152 + y, 127, 208 + y, 1.6, mix(hex(s.outfit_color), [0, 0, 0], 0.45));
        c.capsule(100, 188 + y, 160, 188 + y, 4.5, darker(s.outfit_color, 0.6), { spec: 0.4 });
        [[123, 183, 133, 183], [133, 183, 133, 193], [133, 193, 123, 193], [123, 193, 123, 183]].forEach(function (q) {
          c.line(q[0], q[1] + y, q[2], q[3] + y, 1.6, [214, 184, 100]);
        });
        [166, 176].forEach(function (by) { c.disc(121, by + y, 2.2, mix(hex(s.outfit_color), [0, 0, 0], 0.6)); c.disc(140, by + y, 2.2, mix(hex(s.outfit_color), [0, 0, 0], 0.6)); });
      } else if (s.outfit === "suit") {
        c.ellipsoid(130, 166 + y, 9, 18, pal("#ffffff"), { spec: 0.15 });
        c.capsule(130, 158 + y, 130, 184 + y, 3.4, pal(s.tie_color), { spec: 0.5, gloss: 30 });
        c.ellipsoid(130, 156 + y, 4.5, 3.6, pal(s.tie_color), { spec: 0.4 });
        c.ellipsoid(118, 164 + y, 6, 17, darker(s.outfit_color, 0.2), { angle: 22, spec: 0.2 });
        c.ellipsoid(142, 164 + y, 6, 17, darker(s.outfit_color, 0.2), { angle: -22, spec: 0.2 });
        c.disc(130, 192 + y, 2.2, mix(hex(s.outfit_color), [0, 0, 0], 0.55));
      } else if (s.outfit === "hoodie") {
        var dk = mix(hex(s.outfit_color), [0, 0, 0], 0.35);
        [[114, 184, 146, 184], [146, 184, 146, 198], [146, 198, 114, 198], [114, 198, 114, 184]].forEach(function (q) {
          c.line(q[0], q[1] + y, q[2], q[3] + y, 1.6, dk);
        });
        c.line(124, 156 + y, 123, 172 + y, 2, [250, 250, 250]);
        c.line(136, 156 + y, 137, 172 + y, 2, [250, 250, 250]);
        c.disc(123, 173 + y, 2.2, [250, 250, 250]); c.disc(137, 173 + y, 2.2, [250, 250, 250]);
      } else if (s.outfit === "cardigan") {
        c.ellipsoid(130, 180 + y, 9, 27, FUR, { spec: 0.15 });
        [164, 177, 190].forEach(function (by) { c.disc(140, by + y, 2.4, mix(hex(s.outfit_color), [255, 255, 255], 0.6)); });
      }
    }

    // left arm (sleeve + paw)
    var hx = 100, hy = 190 + y + 3 * swing;
    c.capsule(110, 160 + y, hx, hy, 10, sleeved ? CLOTH : LIMB);
    if (sleeved) c.ellipsoid(hx - 1, hy + 4, 8, 7, LIMB);

    if (sp.human) drawHumanHead(c, s, pose, y, FUR);
    else {
    // ears behind the head
    var INNER = sp.inner ? pal(sp.inner) : null;
    var earsLater = sp.ears === "floppy" || (sp.ears === "long" && s.hat !== "none");
    var earCol = sp.ears === "round" && sp.limbs === "accent" ? ACC : FUR;
    if (!earsLater) drawEars(c, sp.ears, y, earCol, INNER, s);

    // head
    c.ellipsoid(130, 122 + y, 47, 40, FUR, { spec: 0.3 });
    if (sp.cheeks) { c.ellipsoid(112, 138 + y, 15, 10, pal(sp.muzzle), { spec: 0.15 }); c.ellipsoid(148, 138 + y, 15, 10, pal(sp.muzzle), { spec: 0.15 }); }
    if (sp.patches && s.eyewear !== "shades") {
      c.ellipsoid(111, 124 + y, 12, 13.5, ACC, { angle: 12, spec: 0.12 });
      c.ellipsoid(149, 124 + y, 12, 13.5, ACC, { angle: -12, spec: 0.12 });
    }
    if (sp.muzzle) c.ellipsoid(130, 136 + y, 15, 10, pal(sp.muzzle), { spec: 0.18 });

    // eyes (hidden behind shades)
    if (s.eyewear !== "shades") {
      [111, 149].forEach(function (ex) {
        if (pose.blink) {
          if (s.expression === "cheery") c.arc(ex, 125 + y, 6, 5, 200, 340, 2.4, sp.patches ? WHITE : INK);
          else c.line(ex - 6, 123 + y, ex + 6, 123 + y, 2.4, sp.patches ? WHITE : INK);
          return;
        }
        if (s.expression === "cheery") {
          c.ellipsoid(ex, 122 + y, 7.5, 9, EYE, { spec: 0.6, gloss: 40 });
          c.disc(ex - 2.5, 118.5 + y, 2.8, WHITE);
          c.disc(ex + 2.5, 125.5 + y, 1.3, WHITE);
        } else {
          // half-lidded: a shorter eye under a straight (deadpan) or tilted (smug) lid line
          var tilt = s.expression === "smug" ? (ex < 130 ? -1.5 : 1.5) : 0;
          c.ellipsoid(ex, 124 + y, 7.5, 5.5, EYE, { spec: 0.6, gloss: 40 });
          c.disc(ex - 2.5, 122.5 + y, 2, WHITE);
          c.line(ex - 9, 120 + y - tilt, ex + 9, 120 + y + tilt, 2.4, sp.patches ? [10, 10, 14] : INK);
        }
      });
    }

    if (sp.whiskers) {
      [[-1, 0], [-1, 4], [1, 0], [1, 4]].forEach(function (w) {
        var x0 = 130 + w[0] * 14, x1 = 130 + w[0] * 32;
        c.line(x0, 137 + w[1] + y, x1, 134 + w[1] * 1.6 + y, 1.2, [90, 90, 100]);
      });
    }

    // blush
    var blush = { cheery: 0.55, smug: 0.35, deadpan: 0.18 }[s.expression] || 0.4;
    c.soft(97, 140 + y, 9, 5.5, PINK, blush);
    c.soft(163, 140 + y, 9, 5.5, PINK, blush);

    // nose + mouth
    c.ellipsoid(130, 132 + y, 5.5, 4, pal(sp.patches || s.species === "fox" ? "#2a2a32" : "#3a2c32"), { spec: 0.8, gloss: 30 });
    if (s.expression === "cheery") {
      c.line(130, 135 + y, 130, 138 + y, 1.6, INK);
      c.arc(127, 138 + y, 3, 3, 0, 180, 1.8, INK);
      c.arc(133, 138 + y, 3, 3, 0, 180, 1.8, INK);
    } else if (s.expression === "smug") {
      c.line(130, 135 + y, 130, 138 + y, 1.6, INK);
      c.arc(134, 137 + y, 5, 4, 15, 150, 1.8, INK);
    } else {
      c.line(125, 142 + y, 135, 142 + y, 2, INK);
    }

    if (sp.ears === "floppy") drawEars(c, "floppy", y, ACC, null, s);
    }

    if (s.outfit === "trench") {
      c.ellipsoid(112, 157 + y, 15, 8, darker(s.outfit_color, 0.1), { angle: -35, spec: 0.2 });
      c.ellipsoid(148, 157 + y, 15, 8, darker(s.outfit_color, 0.1), { angle: 35, spec: 0.2 });
    }

    // eyewear
    if (s.eyewear === "shades") {
      [111, 149].forEach(function (lx) { c.ellipsoid(lx, 122 + y, 13, 9.5, LENS, { spec: 0.9, gloss: 22 }); });
      c.line(124, 119 + y, 136, 119 + y, 2.4, INK);
      c.line(98, 119 + y, 87, 115 + y, 2.2, INK);
      c.line(162, 119 + y, 173, 115 + y, 2.2, INK);
      c.line(104, 126 + y, 110, 117 + y, 2.2, WHITE, 0.35);
      c.line(142, 126 + y, 148, 117 + y, 2.2, WHITE, 0.35);
    } else if (s.eyewear === "glasses") {
      [111, 149].forEach(function (lx) { c.soft(lx, 122 + y, 11, 11, WHITE, 0.18); c.arc(lx, 122 + y, 11, 11, 0, 360, 2.2, INK); });
      c.line(122, 120 + y, 138, 120 + y, 2, INK);
    }

    // hats
    if (s.hat === "fedora") {
      c.ellipsoid(130, 90 + y, 46, 7.5, HATC, { spec: 0.35 });
      c.ellipsoid(130, 75 + y, 28, 17, HATC, { spec: 0.4 });
      c.capsule(104, 85 + y, 156, 85 + y, 3.6, darker(s.hat_color, 0.6), { spec: 0.3 });
      c.line(124, 61 + y, 136, 61 + y, 2.2, mix(hex(s.hat_color), [0, 0, 0], 0.6));
    } else if (s.hat === "beanie") {
      c.ellipsoid(130, 88 + y, 43, 22, HATC, { spec: 0.25 });
      c.capsule(90, 100 + y, 170, 100 + y, 6.5, darker(s.hat_color, 0.2), { spec: 0.2 });
      c.ellipsoid(130, 64 + y, 8.5, 8, lighter(s.hat_color, 0.4), { spec: 0.15 });
    } else if (s.hat === "bow") {
      c.ellipsoid(152, 86 + y, 11, 7, HATC, { angle: 25, spec: 0.45 });
      c.ellipsoid(170, 78 + y, 11, 7, HATC, { angle: 25, spec: 0.45 });
      c.ellipsoid(161, 82 + y, 4.5, 4.5, darker(s.hat_color, 0.15), { spec: 0.4 });
    }
    if (sp.ears === "long" && s.hat !== "none") drawEars(c, "long", y, FUR, pal(sp.inner), s);

    // right arm last so a raised paw sits in front
    var ang = (pose.arm === undefined ? 60 : pose.arm) * Math.PI / 180;
    var ax = 154, ay = 164 + y;
    c.capsule(ax, ay, ax + 26 * Math.cos(ang), ay + 26 * Math.sin(ang), 10, sleeved ? CLOTH : LIMB);
    var px = ax + 33 * Math.cos(ang), py = ay + 33 * Math.sin(ang);
    if (pose.peace) {
      [-18, 18].forEach(function (sd) {
        var fa = ang + (sd + (pose.tilt || 0)) * Math.PI / 180;
        c.capsule(px, py, px + 21 * Math.cos(fa), py + 21 * Math.sin(fa), 4.2, LIMB, { spec: 0.4 });
      });
    }
    c.ellipsoid(px, py, 8, 8, LIMB);
  }


  /** Original human head: skin, simple glossy eyes, brows, hair styles. */
  function drawHumanHead(c, s, pose, y, SKIN) {
    var HAIR = pal(s.hair_color), hair = s.hair || "short";
    var browCol = mix(hex(s.hair_color), [0, 0, 0], 0.25);
    var skinShade = pal(rgbHex(mix(hex(s.fur), [60, 30, 30], 0.18)));
    var curls = function (cx, cy, rx, ry, a0, a1, step, r) {
      for (var a = a0; a <= a1; a += step) {
        var t = a * Math.PI / 180;
        c.ellipsoid(cx + rx * Math.cos(t), cy + ry * Math.sin(t), r, r, HAIR, { spec: 0.35 });
      }
    };

    // hair behind the head
    if (hair === "bob") c.ellipsoid(130, 126 + y, 51, 44, HAIR, { maxY: 160 + y, spec: 0.3 });
    if (hair === "curly") curls(130, 120 + y, 44, 38, 140, 400, 20, 14);

    // ears + head
    c.ellipsoid(88, 127 + y, 7, 9, SKIN, { spec: 0.2 });
    c.ellipsoid(172, 127 + y, 7, 9, SKIN, { spec: 0.2 });
    c.ellipsoid(130, 122 + y, 43, 40, SKIN, { spec: 0.25 });

    // eyes + brows
    if (s.eyewear !== "shades") {
      [111, 149].forEach(function (ex) {
        if (pose.blink) {
          if (s.expression === "cheery") c.arc(ex, 126 + y, 6, 5, 200, 340, 2.4, INK);
          else c.line(ex - 6, 124 + y, ex + 6, 124 + y, 2.4, INK);
        } else if (s.expression === "cheery") {
          c.ellipsoid(ex, 123 + y, 6.5, 8, EYE, { spec: 0.6, gloss: 40 });
          c.disc(ex - 2.2, 119.5 + y, 2.4, WHITE);
          c.disc(ex + 2.2, 126 + y, 1.1, WHITE);
        } else {
          var tilt = s.expression === "smug" ? (ex < 130 ? -1.2 : 1.2) : 0;
          c.ellipsoid(ex, 125 + y, 6.5, 5, EYE, { spec: 0.6, gloss: 40 });
          c.disc(ex - 2.2, 123.5 + y, 1.8, WHITE);
          c.line(ex - 8, 121 + y - tilt, ex + 8, 121 + y + tilt, 2.2, INK);
        }
      });
    }
    var raise = s.expression === "smug" ? 3 : 0, lower = s.expression === "deadpan" ? 1.5 : 0;
    c.line(104, 110 + y + lower, 118, 109 + y + lower, 2.8, browCol);
    c.line(142, 109 + y + lower - raise, 156, 110 + y + lower - raise * 1.6, 2.8, browCol);

    // blush, nose, mouth
    var blush = { cheery: 0.45, smug: 0.3, deadpan: 0.15 }[s.expression] || 0.35;
    c.soft(98, 139 + y, 9, 5.5, PINK, blush);
    c.soft(162, 139 + y, 9, 5.5, PINK, blush);
    c.ellipsoid(130, 133 + y, 4.2, 3.2, skinShade, { spec: 0.35 });
    if (s.expression === "cheery") c.arc(130, 140 + y, 6, 4, 10, 170, 2, INK);
    else if (s.expression === "smug") c.arc(133, 140 + y, 5, 3.5, 15, 150, 1.9, INK);
    else c.line(125, 143 + y, 135, 143 + y, 2, INK);

    // hair on top
    if (hair === "short") {
      c.ellipsoid(130, 114 + y, 46, 38, HAIR, { maxY: 100 + y, spec: 0.35 });
      c.ellipsoid(118, 99 + y, 24, 9, HAIR, { angle: -14, spec: 0.35 });
    } else if (hair === "bob" || hair === "long") {
      c.ellipsoid(130, 116 + y, 47, 38, HAIR, { maxY: 103 + y, spec: 0.35 });
      c.ellipsoid(112, 103 + y, 27, 10, HAIR, { angle: -16, spec: 0.35 });
      var drop = hair === "bob" ? 22 : 34;
      c.ellipsoid(89, 118 + y + drop / 2, 9, drop, HAIR, { spec: 0.3 });
      c.ellipsoid(171, 118 + y + drop / 2, 9, drop, HAIR, { spec: 0.3 });
    } else if (hair === "curly") {
      curls(130, 112 + y, 40, 26, 195, 345, 21, 11);
    } else if (hair === "bun") {
      c.ellipsoid(130, 116 + y, 46, 38, HAIR, { maxY: 101 + y, spec: 0.45 });
      c.ellipsoid(130, 76 + y, 15, 14, HAIR, { spec: 0.4 });
    } else if (hair === "buzz") {
      c.ellipsoid(130, 118 + y, 44, 38, pal(rgbHex(mix(hex(s.hair_color), hex(s.fur), 0.35))), { maxY: 97 + y, spec: 0.15 });
    }
  }

  function rgbHex(v) {
    return "#" + v.map(function (x) { return ("0" + Math.round(Math.max(0, Math.min(255, x))).toString(16)).slice(-2); }).join("");
  }


  /**
   * Side view, facing left, for walking. Legs stride and arms swing front/back.
   * The apps mirror these frames for walking right.
   */
  function drawSide(c, spec, pose) {
    var s = resolve(spec), sp = s.sp;
    var swing = pose.swing || 0, y = Math.abs(swing) * 2.5;
    var FUR = pal(s.fur), ACC = pal(s.accent), LIMB = sp.limbs === "accent" ? ACC : FUR;
    var dressed = s.outfit !== "none", sleeved = dressed || !!sp.human;
    var CLOTH = pal(s.outfit_color), HATC = pal(s.hat_color);
    var LEGS = sp.human ? pal("#3a3340") : LIMB;
    var dim = function (col) { return [mix(col[0], [0, 0, 0], 0.18), mix(col[1], [0, 0, 0], 0.18)]; };
    var HAIR = pal(s.hair_color || "#3b2a22");
    var deg = Math.PI / 180;

    c.soft(130, 226, 36 - y, 7, [40, 34, 70], 0.26);

    // far leg + far arm (behind the body, a little darker)
    c.ellipsoid(130 + 9 * swing, 210 + y - 3 * Math.max(0, swing), 12, 12, dim(LEGS));
    var fa = (90 + 32 * swing) * deg;
    c.capsule(132, 160 + y, 132 + 26 * Math.cos(fa), 160 + y + 26 * Math.sin(fa), 9.5, dim(sleeved ? CLOTH : LIMB));
    c.ellipsoid(132 + 31 * Math.cos(fa), 160 + y + 31 * Math.sin(fa), 7.5, 7.5, dim(LIMB));

    // long hair behind
    if (sp.human && s.hair === "long") c.ellipsoid(146, 150 + y, 28, 50, HAIR, { maxY: 190 + y, spec: 0.3 });

    // near leg
    c.ellipsoid(130 - 9 * swing, 210 + y - 3 * Math.max(0, -swing), 12, 12, LEGS);

    // hood behind the head
    if (s.outfit === "hoodie") c.ellipsoid(146, 146 + y, 26, 20, CLOTH, { spec: 0.15 });

    // body (narrower in profile) + outfit details on the front edge
    var BODY = dressed || sp.human ? CLOTH : FUR;
    c.ellipsoid(130, 178 + y, 26, 32, BODY, { spec: 0.22 });
    if (!dressed && !sp.human) c.ellipsoid(118, 183 + y, 11, 19, pal(rgbHex(mix(hex(s.fur), [255, 255, 255], 0.55))), { spec: 0.1 });
    if (s.outfit === "trench") {
      c.capsule(105, 188 + y, 155, 188 + y, 4.5, pal(rgbHex(mix(hex(s.outfit_color), [0, 0, 0], 0.6))), { spec: 0.4 });
      c.ellipsoid(118, 157 + y, 14, 8, pal(rgbHex(mix(hex(s.outfit_color), [0, 0, 0], 0.1))), { angle: -30, spec: 0.2 });
    } else if (s.outfit === "suit") {
      c.ellipsoid(108, 166 + y, 4, 16, pal("#ffffff"), { spec: 0.15 });
      c.capsule(107, 160 + y, 106, 182 + y, 2.6, pal(s.tie_color), { spec: 0.5 });
    } else if (s.outfit === "cardigan") {
      c.ellipsoid(109, 180 + y, 5, 24, FUR, { spec: 0.15 });
    } else if (s.outfit === "hoodie") {
      c.line(112, 156 + y, 110, 172 + y, 2, [250, 250, 250]);
    }

    // ears / hair behind the head
    var INNER = sp.inner ? pal(sp.inner) : null;
    if (!sp.human) {
      var earCol = sp.ears === "round" && sp.limbs === "accent" ? ACC : FUR;
      if (sp.ears === "round") { c.ellipsoid(148, 90 + y, 14, 13, earCol); if (INNER) c.ellipsoid(146, 91 + y, 6.5, 6, INNER, { spec: 0.1 }); }
      if (sp.ears === "pointy") {
        c.ellipsoid(150, 84 + y, 11, 20, dim(FUR), { angle: 16 });
        c.ellipsoid(136, 82 + y, 11, 20, FUR, { angle: 4 });
        if (INNER) c.ellipsoid(134, 85 + y, 5, 12, INNER, { angle: 4, spec: 0.1 });
        if (s.species === "fox") c.ellipsoid(135, 66 + y, 6, 6.5, ACC, { spec: 0.2 });
      }
      if (sp.ears === "long" && s.hat === "none") {
        c.ellipsoid(150, 64 + y, 10, 30, dim(FUR), { angle: 14 });
        c.ellipsoid(136, 62 + y, 10, 30, FUR, { angle: 4 });
        if (INNER) c.ellipsoid(135, 64 + y, 5, 22, INNER, { angle: 4, spec: 0.1 });
      }
    } else {
      if (s.hair === "bob") c.ellipsoid(140, 124 + y, 40, 42, HAIR, { maxY: 158 + y, spec: 0.3 });
      if (s.hair === "curly") for (var a = 200; a <= 470; a += 22) {
        var t = a * deg; c.ellipsoid(134 + 42 * Math.cos(t), 118 + y + 36 * Math.sin(t), 13, 13, HAIR, { spec: 0.35 });
      }
      if (s.hair === "bun") c.ellipsoid(158, 88 + y, 14, 13, HAIR, { spec: 0.4 });
    }

    // head
    var HEAD = FUR;
    c.ellipsoid(130, 122 + y, sp.human ? 40 : 44, 40, HEAD, { spec: 0.3 });
    if (sp.human) c.ellipsoid(140, 128 + y, 6, 8, FUR, { spec: 0.2 });         // ear

    // face, looking left
    var ex = 104, ey = 121 + y;
    if (sp.patches && s.eyewear !== "shades") c.ellipsoid(104, 123 + y, 10, 13, ACC, { angle: 10, spec: 0.12 });
    if (sp.cheeks) c.ellipsoid(104, 139 + y, 14, 9, pal(sp.muzzle), { spec: 0.15 });
    if (!sp.human) {
      c.ellipsoid(92, 135 + y, 14, 10, sp.muzzle ? pal(sp.muzzle) : FUR, { spec: 0.2 });
      c.ellipsoid(80, 131 + y, 5, 4, pal(sp.patches || s.species === "fox" ? "#2a2a32" : "#3a2c32"), { spec: 0.8, gloss: 30 });
    } else {
      c.ellipsoid(90, 131 + y, 5, 5, pal(rgbHex(mix(hex(s.fur), [60, 30, 30], 0.12))), { spec: 0.3 });
    }
    if (s.eyewear !== "shades") {
      if (s.expression === "cheery") {
        c.ellipsoid(ex, ey + 1, 5.5, 8, EYE, { spec: 0.6, gloss: 40 });
        c.disc(ex - 2, ey - 2, 2.2, WHITE);
      } else {
        c.ellipsoid(ex, ey + 3, 5.5, 5, EYE, { spec: 0.6, gloss: 40 });
        c.line(ex - 7, ey - 1, ex + 7, ey - 1 + (s.expression === "smug" ? 1.5 : 0), 2.2, sp.patches ? [10, 10, 14] : INK);
      }
    }
    if (sp.human) c.line(97, 108 + y, 112, 108 + y, 2.6, mix(hex(s.hair_color || "#3b2a22"), [0, 0, 0], 0.25));
    if (sp.whiskers) { c.line(88, 137 + y, 66, 133 + y, 1.2, [90, 90, 100]); c.line(88, 140 + y, 66, 141 + y, 1.2, [90, 90, 100]); }
    c.soft(114, 139 + y, 8, 5, PINK, { cheery: 0.5, smug: 0.32, deadpan: 0.16 }[s.expression] || 0.35);
    if (s.expression === "cheery") c.arc(sp.human ? 96 : 89, 141 + y, 4, 3, 10, 170, 1.8, INK);
    else c.line(sp.human ? 92 : 86, 143 + y, sp.human ? 100 : 94, 143 + y, 1.8, INK);

    if (s.outfit === "trench") c.ellipsoid(140, 156 + y, 13, 8, pal(rgbHex(mix(hex(s.outfit_color), [0, 0, 0], 0.1))), { angle: 30, spec: 0.2 });
    if (!sp.human && sp.ears === "floppy") c.ellipsoid(142, 122 + y, 11, 24, ACC, { angle: -12 });

    // hair on top (humans)
    if (sp.human) {
      if (s.hair === "short") { c.ellipsoid(134, 114 + y, 42, 36, HAIR, { maxY: 102 + y, spec: 0.35 }); c.ellipsoid(108, 101 + y, 16, 8, HAIR, { angle: -18, spec: 0.35 }); }
      else if (s.hair === "bob" || s.hair === "long") { c.ellipsoid(134, 116 + y, 43, 37, HAIR, { maxY: 104 + y, spec: 0.35 }); c.ellipsoid(106, 104 + y, 16, 9, HAIR, { angle: -20, spec: 0.35 }); c.ellipsoid(156, 126 + y, 12, s.hair === "bob" ? 26 : 36, HAIR, { spec: 0.3 }); }
      else if (s.hair === "curly") for (var b = 190; b <= 330; b += 24) { var u = b * deg; c.ellipsoid(132 + 38 * Math.cos(u), 112 + y + 24 * Math.sin(u), 11, 11, HAIR, { spec: 0.35 }); }
      else if (s.hair === "bun") c.ellipsoid(134, 116 + y, 42, 37, HAIR, { maxY: 101 + y, spec: 0.45 });
      else if (s.hair === "buzz") c.ellipsoid(134, 118 + y, 41, 37, pal(rgbHex(mix(hex(s.hair_color), hex(s.fur), 0.35))), { maxY: 97 + y, spec: 0.15 });
    }

    // eyewear in profile
    if (s.eyewear === "shades") {
      c.ellipsoid(101, 121 + y, 9, 8, LENS, { spec: 0.9, gloss: 22 });
      c.line(108, 118 + y, 140, 116 + y, 2.2, INK);
    } else if (s.eyewear === "glasses") {
      c.arc(103, 121 + y, 8.5, 9, 0, 360, 2, INK);
      c.line(111, 119 + y, 140, 117 + y, 2, INK);
    }

    // hats
    if (s.hat === "fedora") {
      c.ellipsoid(128, 90 + y, 46, 7.5, HATC, { spec: 0.35 });
      c.ellipsoid(132, 75 + y, 27, 17, HATC, { spec: 0.4 });
      c.capsule(106, 85 + y, 158, 85 + y, 3.6, pal(rgbHex(mix(hex(s.hat_color), [0, 0, 0], 0.6))), { spec: 0.3 });
    } else if (s.hat === "beanie") {
      c.ellipsoid(132, 88 + y, 42, 22, HATC, { spec: 0.25 });
      c.capsule(92, 100 + y, 172, 100 + y, 6.5, pal(rgbHex(mix(hex(s.hat_color), [0, 0, 0], 0.2))), { spec: 0.2 });
      c.ellipsoid(136, 64 + y, 8.5, 8, pal(rgbHex(mix(hex(s.hat_color), [255, 255, 255], 0.4))), { spec: 0.15 });
    } else if (s.hat === "bow") {
      c.ellipsoid(150, 84 + y, 10, 6.5, HATC, { angle: 60, spec: 0.45 });
      c.ellipsoid(160, 94 + y, 10, 6.5, HATC, { angle: 60, spec: 0.45 });
      c.ellipsoid(155, 89 + y, 4, 4, HATC, { spec: 0.4 });
    }
    if (!sp.human && sp.ears === "long" && s.hat !== "none") {
      c.ellipsoid(136, 62 + y, 10, 30, FUR, { angle: 4 });
      if (INNER) c.ellipsoid(135, 64 + y, 5, 22, INNER, { angle: 4, spec: 0.1 });
    }

    // near arm in front
    var na = (90 - 32 * swing) * deg;
    c.capsule(128, 160 + y, 128 + 26 * Math.cos(na), 160 + y + 26 * Math.sin(na), 10, sleeved ? CLOTH : LIMB);
    c.ellipsoid(128 + 31 * Math.cos(na), 160 + y + 31 * Math.sin(na), 8, 8, LIMB);
  }

  /** Mirror an RGBA image left-right (walking right). */
  function flip(img) {
    var w = img.width, h = img.height, out = new Uint8ClampedArray(img.data.length);
    for (var yy = 0; yy < h; yy++) for (var xx = 0; xx < w; xx++) {
      var a = (yy * w + xx) * 4, b = (yy * w + (w - 1 - xx)) * 4;
      out[b] = img.data[a]; out[b + 1] = img.data[a + 1]; out[b + 2] = img.data[a + 2]; out[b + 3] = img.data[a + 3];
    }
    return { width: w, height: h, data: out };
  }

  function drawEars(c, kind, y, col, inner, s) {
    if (kind === "round") {
      [92, 168].forEach(function (ex) {
        c.ellipsoid(ex, 90 + y, 15, 14, col);
        if (inner) c.ellipsoid(ex, 91 + y, 7.5, 7, inner, { spec: 0.1 });
      });
    } else if (kind === "pointy") {
      [[97, -18], [163, 18]].forEach(function (e) {
        c.ellipsoid(e[0], 86 + y, 12, 21, col, { angle: e[1] });
        if (inner) c.ellipsoid(e[0], 89 + y, 6, 13, inner, { angle: e[1], spec: 0.1 });
        if (s.species === "fox") c.ellipsoid(e[0] + (e[1] < 0 ? -5 : 5), 70 + y, 6, 6.5, pal(s.accent), { spec: 0.2 });
      });
    } else if (kind === "long") {
      [[112, -10], [148, 10]].forEach(function (e) {
        c.ellipsoid(e[0], 64 + y, 10, 30, col, { angle: e[1] });
        if (inner) c.ellipsoid(e[0], 66 + y, 5, 22, inner, { angle: e[1], spec: 0.1 });
      });
    } else if (kind === "floppy") {
      [[86, 22], [174, -22]].forEach(function (e) {
        c.ellipsoid(e[0], 118 + y, 12, 24, col, { angle: e[1] });
      });
    }
  }

  // ------------------------------------------------------------- poses
  /** Frame list. walkL0-7 are side views facing left; walkR0-7 are their mirror images. */
  function poses() {
    var list = [], i, w;
    for (i = 0; i < 8; i++) {
      w = Math.sin(i / 8 * 2 * Math.PI);
      list.push(["walkL" + i, { side: true, swing: w }]);
      list.push(["walkR" + i, { side: true, swing: w, mirror: true }]);
    }
    list.push(["idle", {}]);
    list.push(["blink", { blink: true }]);
    for (i = 0; i < 6; i++) list.push(["reach" + i, { arm: 60 - 105 * i / 5 }]);
    for (i = 0; i < 8; i++) {
      w = Math.sin(i / 8 * 2 * Math.PI);
      list.push(["peace" + i, { arm: -55 + 4 * w, peace: true, tilt: 4 * w }]);
    }
    return list;
  }

  /** Render one pose. scale = output px per design unit. Returns {width, height, data(RGBA)}. */
  function render(spec, pose, scale, ss) {
    ss = ss || 3;
    var c = new Canvas(scale, ss);
    if (pose && pose.side) drawSide(c, spec, pose); else drawCharacter(c, spec, pose);
    var img = c.toRGBA(ss);
    return pose && pose.mirror ? flip(img) : img;
  }

  var api = { BOX_W: BOX_W, BOX_H: BOX_H, SPECIES: SPECIES, OPTIONS: OPTIONS, SKINS: SKINS, DEFAULTS: DEFAULTS, render: render, poses: poses, resolve: resolve, flip: flip };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  else root.BonkRender = api;
})(this);
