// Builds tools/studio.html (a single self-contained page) from the template + renderer.
const fs = require("fs"), path = require("path");
const dir = __dirname;
const tpl = fs.readFileSync(path.join(dir, "studio.template.html"), "utf8");
const renderer = fs.readFileSync(path.join(dir, "bonk-render.js"), "utf8").replace(/<\/script/gi, "<\\/script");
const personas = fs.readFileSync(path.join(dir, "..", "characters", "personalities.json"), "utf8");
fs.writeFileSync(path.join(dir, "studio.html"), tpl.replace("/*RENDERER*/", () => renderer).replace("/*PERSONAS*/", () => personas.trim()));
console.log("wrote tools/studio.html");
