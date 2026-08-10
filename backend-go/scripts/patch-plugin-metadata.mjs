import fs from "node:fs";

const file = process.argv[2];
const localVersion = process.argv[3];
if (!file || !localVersion) {
  throw new Error("usage: node patch-plugin-metadata.mjs <plugin.xml> <local-version>");
}

const source = fs.readFileSync(file, "utf8");
let pattern;
let replacement;
if (file.endsWith("plugin.xml")) {
  pattern = /<version>[^<]+<\/version>/g;
  replacement = `<version>${localVersion}</version>`;
} else if (file.endsWith("MANIFEST.MF")) {
  pattern = /^Version: .*$/gm;
  replacement = `Version: ${localVersion}`;
} else {
  throw new Error(`unsupported plugin metadata file: ${file}`);
}

const matches = source.match(pattern) ?? [];
if (matches.length !== 1) {
  throw new Error(`plugin version: expected one entry in ${file}, found ${matches.length}`);
}

const patched = source.replace(pattern, replacement);
fs.writeFileSync(file, patched);
