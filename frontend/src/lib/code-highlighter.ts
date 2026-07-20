import { createHighlighterCore } from "shiki/core";
import { createJavaScriptRegexEngine } from "shiki/engine/javascript";
import githubDark from "@shikijs/themes/github-dark";
import bash from "@shikijs/langs/bash";
import c from "@shikijs/langs/c";
import cpp from "@shikijs/langs/cpp";
import csharp from "@shikijs/langs/csharp";
import css from "@shikijs/langs/css";
import diff from "@shikijs/langs/diff";
import go from "@shikijs/langs/go";
import html from "@shikijs/langs/html";
import java from "@shikijs/langs/java";
import javascript from "@shikijs/langs/javascript";
import json from "@shikijs/langs/json";
import jsx from "@shikijs/langs/jsx";
import kotlin from "@shikijs/langs/kotlin";
import markdown from "@shikijs/langs/markdown";
import php from "@shikijs/langs/php";
import python from "@shikijs/langs/python";
import ruby from "@shikijs/langs/ruby";
import rust from "@shikijs/langs/rust";
import sql from "@shikijs/langs/sql";
import swift from "@shikijs/langs/swift";
import tsx from "@shikijs/langs/tsx";
import typescript from "@shikijs/langs/typescript";
import xml from "@shikijs/langs/xml";
import yaml from "@shikijs/langs/yaml";

const languages = [
  bash, c, cpp, csharp, css, diff, go, html, java, javascript, json, jsx,
  kotlin, markdown, php, python, ruby, rust, sql, swift, tsx, typescript, xml, yaml,
];

const aliases: Record<string, string> = {
  cxx: "cpp",
  h: "c",
  hpp: "cpp",
  cs: "csharp",
  html5: "html",
  js: "javascript",
  kt: "kotlin",
  kts: "kotlin",
  md: "markdown",
  py: "python",
  rb: "ruby",
  rs: "rust",
  sh: "bash",
  shell: "bash",
  ts: "typescript",
  yml: "yaml",
};

const supported = new Set([
  "bash", "c", "cpp", "csharp", "css", "diff", "go", "html", "java", "javascript",
  "json", "jsx", "kotlin", "markdown", "php", "python", "ruby", "rust", "sql", "swift",
  "tsx", "typescript", "xml", "yaml",
]);
const highlighter = createHighlighterCore({
  themes: [githubDark],
  langs: languages,
  engine: createJavaScriptRegexEngine(),
});

export async function highlightCode(source: string, language: string): Promise<string> {
  const candidate = language.trim().toLowerCase();
  const normalized = aliases[candidate] ?? candidate;
  const lang = supported.has(normalized) ? normalized : "text";
  const engine = await highlighter;
  return engine.codeToHtml(source, { lang, theme: "github-dark" });
}
