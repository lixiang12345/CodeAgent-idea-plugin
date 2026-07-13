#!/usr/bin/env node
/**
 * Validate the single-file frontend payload for the IDEA JCEF host.
 * The plugin loads this file via file:// (not data: loadHTML). Keep
 * type=module so the Vite/Svelte bundle remains valid JS.
 */
import { readFileSync } from "node:fs";
import { resolve } from "node:path";

const target = resolve(import.meta.dirname, "../dist/index.html");
const html = readFileSync(target, "utf8");

if (!html.includes('id="app"')) {
  throw new Error("frontend dist/index.html is missing #app mount");
}
if (!html.includes("<!doctype html>") && !html.includes("<!DOCTYPE html>")) {
  throw new Error("frontend dist/index.html is missing doctype");
}
if (!html.includes("<script")) {
  throw new Error("frontend dist/index.html is missing bundled script");
}

console.log(
  "jcef-postbuild: validated single-file frontend",
  `(${html.length} bytes, module=${html.includes('type="module"')})`,
);
