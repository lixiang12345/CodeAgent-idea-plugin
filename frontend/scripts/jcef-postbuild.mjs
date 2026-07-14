#!/usr/bin/env node
/**
 * Validate the multi-asset frontend payload used by the IDEA JCEF host.
 * Expected layout (same shape as original Augment webviews):
 *   dist/index.html
 *   dist/assets/*.js|*.css
 */
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, resolve } from "node:path";

const dist = resolve(import.meta.dirname, "../dist");
const index = join(dist, "index.html");
const assets = join(dist, "assets");

if (!existsSync(index)) throw new Error("frontend dist/index.html missing");
if (!existsSync(assets) || !statSync(assets).isDirectory()) {
  throw new Error("frontend dist/assets/ missing — multi-asset build required for JCEF");
}

const html = readFileSync(index, "utf8");
if (!html.includes('id="app"')) throw new Error("frontend dist/index.html missing #app");
if (!html.includes("<!doctype html>") && !html.includes("<!DOCTYPE html>")) {
  throw new Error("frontend dist/index.html missing doctype");
}

// Must reference external assets, not a multi-MB inline bundle.
const externalScripts = [...html.matchAll(/src="(\.\/assets\/[^"]+\.js)"/g)].map((m) => m[1]);
const externalStyles = [...html.matchAll(/href="(\.\/assets\/[^"]+\.css)"/g)].map((m) => m[1]);
if (externalScripts.length === 0) {
  throw new Error("frontend dist/index.html has no external ./assets/*.js references");
}

const assetFiles = readdirSync(assets);
const jsCount = assetFiles.filter((name) => name.endsWith(".js")).length;
const cssCount = assetFiles.filter((name) => name.endsWith(".css")).length;
if (jsCount === 0) throw new Error("frontend dist/assets has no JS files");

const indexSize = statSync(index).size;
if (indexSize > 200_000) {
  throw new Error(`frontend dist/index.html is too large (${indexSize} bytes); expected multi-asset entry HTML`);
}

console.log(
  "jcef-postbuild: multi-asset frontend ok",
  `html=${indexSize}B scripts=${externalScripts.length} styles=${externalStyles.length} assets=${jsCount}js/${cssCount}css`,
);
