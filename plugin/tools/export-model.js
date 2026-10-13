#!/usr/bin/env node
/* ============================================================
   Writes the browser model (models/flip-model.js, which assigns
   window.OSF_MODEL) as plain JSON for the plugin:
   plugin/src/main/resources/com/geflipper/flip-model.json

     node plugin/tools/export-model.js           # (re)write the resource
     node plugin/tools/export-model.js --check   # exit 1 if it is out of date
   ============================================================ */
"use strict";
const fs = require("fs");
const path = require("path");

const ROOT = path.join(__dirname, "..", "..");
const SRC = path.join(ROOT, "models", "flip-model.js");
const OUT = path.join(ROOT, "plugin", "src", "main", "resources", "com", "geflipper", "flip-model.json");

global.window = {};
require(SRC);
const model = global.window.OSF_MODEL;
if (!model || !Array.isArray(model.features) || !model.boosters) {
  console.error(`${SRC} did not define window.OSF_MODEL`);
  process.exit(1);
}
const json = JSON.stringify(model) + "\n";

if (process.argv.includes("--check")) {
  const cur = fs.existsSync(OUT) ? fs.readFileSync(OUT, "utf8") : null;
  if (cur !== json) {
    console.error(`${path.relative(ROOT, OUT)} is out of date: run node plugin/tools/export-model.js`);
    process.exit(1);
  }
  console.log(`${path.relative(ROOT, OUT)} is up to date`);
} else {
  fs.writeFileSync(OUT, json);
  console.log(`wrote ${path.relative(ROOT, OUT)} (${model.features.length} features, ${Object.keys(model.boosters).join(", ")})`);
}
