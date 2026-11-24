#!/usr/bin/env node
/*
 * Renders every character in characters/*.json into sprite frames for both apps.
 *
 *   node tools/render.js               render all characters
 *   node tools/render.js ace-fox       render just one
 *
 * Output:
 *   android/app/src/main/assets/characters/<id>/*.png   2x, cropped, soft edges
 *   windows/characters/<id>/*.png                       0.8x, hard edges (Tk colour-key)
 * plus character.json, personalities.json and index.json next to them.
 * No npm packages needed.
 */
"use strict";
const fs = require("fs");
const path = require("path");
const zlib = require("zlib");
const R = require("./bonk-render.js");

const ROOT = path.resolve(__dirname, "..");
const SRC = path.join(ROOT, "characters");
const OUT_ANDROID = path.join(ROOT, "android", "app", "src", "main", "assets", "characters");
const OUT_WINDOWS = path.join(ROOT, "windows", "characters");
const ANDROID_SCALE = 2;
const ANDROID_CROP = [60, 40, 215, 240];   // character's area inside the 260x240 box
const WINDOWS_SCALE = 0.8;   // small on the laptop screen

// ------------------------------------------------------------ tiny PNG encoder
const CRC = new Int32Array(256).map((_, n) => {
  let c = n;
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
  return c;
});
function crc32(buf) {
  let c = -1;
  for (const b of buf) c = CRC[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ -1) >>> 0;
}
function chunk(type, data) {
  const len = Buffer.alloc(4); len.writeUInt32BE(data.length);
  const td = Buffer.concat([Buffer.from(type), data]);
  const crc = Buffer.alloc(4); crc.writeUInt32BE(crc32(td));
  return Buffer.concat([len, td, crc]);
}
function png(img) {
  const { width: w, height: h, data } = img;
  const raw = Buffer.alloc((w * 4 + 1) * h);
  for (let y = 0; y < h; y++) {
    raw[y * (w * 4 + 1)] = 0;
    Buffer.from(data.buffer, data.byteOffset + y * w * 4, w * 4).copy(raw, y * (w * 4 + 1) + 1);
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0); ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8; ihdr[9] = 6; ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]),
    chunk("IHDR", ihdr), chunk("IDAT", zlib.deflateSync(raw, { level: 9 })), chunk("IEND", Buffer.alloc(0)),
  ]);
}

// ------------------------------------------------------------ image helpers
function crop(img, x0, y0, x1, y1) {
  const w = x1 - x0, h = y1 - y0, out = new Uint8ClampedArray(w * h * 4);
  for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
    const s = ((y + y0) * img.width + (x + x0)) * 4, d = (y * w + x) * 4;
    for (let c = 0; c < 4; c++) out[d + c] = img.data[s + c];
  }
  return { width: w, height: h, data: out };
}

/** Bilinear resize on premultiplied colour (no dark fringes). */
function resize(img, W, H) {
  const out = new Uint8ClampedArray(W * H * 4), sx = img.width / W, sy = img.height / H;
  const px = (x, y, c) => {
    x = Math.min(img.width - 1, Math.max(0, x)); y = Math.min(img.height - 1, Math.max(0, y));
    const i = (y * img.width + x) * 4, a = img.data[i + 3] / 255;
    return c === 3 ? a : img.data[i + c] * a;
  };
  for (let y = 0; y < H; y++) for (let x = 0; x < W; x++) {
    const fx = (x + 0.5) * sx - 0.5, fy = (y + 0.5) * sy - 0.5;
    const x0 = Math.floor(fx), y0 = Math.floor(fy), tx = fx - x0, ty = fy - y0;
    const v = [0, 0, 0, 0];
    for (let c = 0; c < 4; c++) {
      v[c] = (px(x0, y0, c) * (1 - tx) + px(x0 + 1, y0, c) * tx) * (1 - ty)
           + (px(x0, y0 + 1, c) * (1 - tx) + px(x0 + 1, y0 + 1, c) * tx) * ty;
    }
    const o = (y * W + x) * 4;
    if (v[3] > 0) { out[o] = v[0] / v[3]; out[o + 1] = v[1] / v[3]; out[o + 2] = v[2] / v[3]; }
    out[o + 3] = v[3] * 255;
  }
  return { width: W, height: H, data: out };
}

function hardAlpha(img) {
  const d = img.data;
  for (let i = 3; i < d.length; i += 4) d[i] = d[i] > 120 ? 255 : 0;
  return img;
}

// ------------------------------------------------------------ main
function renderCharacter(id) {
  const spec = JSON.parse(fs.readFileSync(path.join(SRC, id + ".json"), "utf8"));
  const aDir = path.join(OUT_ANDROID, id), wDir = path.join(OUT_WINDOWS, id);
  for (const d of [aDir, wDir]) { fs.rmSync(d, { recursive: true, force: true }); fs.mkdirSync(d, { recursive: true }); }

  for (const [name, pose] of R.poses()) {
    const full = R.render(spec, pose, ANDROID_SCALE, 3);
    const [x0, y0, x1, y1] = ANDROID_CROP.map((v) => Math.round(v * ANDROID_SCALE));
    fs.writeFileSync(path.join(aDir, name + ".png"), png(crop(full, x0, y0, x1, y1)));
    const win = hardAlpha(resize(full, Math.round(R.BOX_W * WINDOWS_SCALE), Math.round(R.BOX_H * WINDOWS_SCALE)));
    fs.writeFileSync(path.join(wDir, name + ".png"), png(win));
  }
  const meta = JSON.stringify(Object.assign({ id }, spec), null, 2);
  fs.writeFileSync(path.join(aDir, "character.json"), meta);
  fs.writeFileSync(path.join(wDir, "character.json"), meta);
  return { id, name: spec.name || id, personality: spec.personality || "cheerleader" };
}

function main() {
  const only = process.argv[2];
  const ids = fs.readdirSync(SRC).filter((f) => f.endsWith(".json") && f !== "personalities.json")
    .map((f) => f.slice(0, -5)).sort();
  const index = [];
  for (const id of ids) {
    if (only && id !== only) {
      index.push({ id, name: JSON.parse(fs.readFileSync(path.join(SRC, id + ".json"), "utf8")).name || id });
      continue;
    }
    const t = Date.now();
    index.push(renderCharacter(id));
    console.log(`rendered ${id} in ${((Date.now() - t) / 1000).toFixed(1)}s`);
  }
  // drop folders of characters that no longer exist
  for (const base of [OUT_ANDROID, OUT_WINDOWS]) {
    fs.mkdirSync(base, { recursive: true });
    for (const d of fs.readdirSync(base)) {
      if (fs.statSync(path.join(base, d)).isDirectory() && !ids.includes(d)) fs.rmSync(path.join(base, d), { recursive: true });
    }
    fs.copyFileSync(path.join(SRC, "personalities.json"), path.join(base, "personalities.json"));
    fs.writeFileSync(path.join(base, "index.json"), JSON.stringify(index, null, 2));
  }
}

main();
