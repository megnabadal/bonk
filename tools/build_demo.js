// Builds tools/demo.html: a self-contained, sped-up simulation of Bonk on a phone.
const fs = require("fs"), path = require("path");
const dir = __dirname, root = path.join(dir, "..");
const chars = fs.readdirSync(path.join(root, "characters"))
  .filter(f => f.endsWith(".json") && f !== "personalities.json").sort()
  .map(f => Object.assign({id: f.slice(0, -5)}, JSON.parse(fs.readFileSync(path.join(root, "characters", f), "utf8"))));
const renderer = fs.readFileSync(path.join(dir, "bonk-render.js"), "utf8").replace(/<\/script/gi, "<\\/script");
const personas = fs.readFileSync(path.join(root, "characters", "personalities.json"), "utf8").trim();
const html = fs.readFileSync(path.join(dir, "demo.template.html"), "utf8")
  .replace("/*RENDERER*/", () => renderer)
  .replace("/*CHARACTERS*/", () => JSON.stringify(chars))
  .replace("/*PERSONAS*/", () => personas);
fs.writeFileSync(path.join(dir, "demo.html"), html);
console.log("wrote tools/demo.html with", chars.length, "characters");
