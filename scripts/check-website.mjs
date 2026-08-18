import { lstat, readFile, readdir } from "node:fs/promises";
import { dirname, extname, posix, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const repositoryRoot = resolve(fileURLToPath(new URL("../", import.meta.url)));
const websiteRoot = resolve(repositoryRoot, "website");
const projectBase = "/CodeAgent-idea-plugin/";
const requiredPages = [
  "index.html",
  "product.html",
  "manual.html",
  "en/index.html",
  "en/product.html",
  "en/manual.html",
];

const errors = [];

async function collectFiles(directory, prefix = "") {
  const entries = await readdir(directory, { withFileTypes: true });
  const files = [];
  for (const entry of entries) {
    const relativePath = posix.join(prefix, entry.name);
    const absolutePath = resolve(directory, entry.name);
    if (entry.isSymbolicLink()) {
      errors.push(`${relativePath}: symbolic links are not supported by GitHub Pages artifacts`);
      continue;
    }
    if (entry.isDirectory()) files.push(...await collectFiles(absolutePath, relativePath));
    else if (entry.isFile()) files.push(relativePath);
  }
  return files;
}

function extractAttributeReferences(source) {
  return [...source.matchAll(/\b(?:href|src)\s*=\s*["']([^"']+)["']/gi)].map((match) => match[1].trim());
}

function extractCssReferences(source) {
  return [...source.matchAll(/url\(\s*(?:"([^"]*)"|'([^']*)'|([^)]*))\s*\)/gi)]
    .map((match) => (match[1] ?? match[2] ?? match[3]).trim());
}

function splitReference(reference) {
  const hashIndex = reference.indexOf("#");
  const beforeHash = hashIndex >= 0 ? reference.slice(0, hashIndex) : reference;
  const hash = hashIndex >= 0 ? reference.slice(hashIndex + 1) : "";
  const queryIndex = beforeHash.indexOf("?");
  return {
    path: queryIndex >= 0 ? beforeHash.slice(0, queryIndex) : beforeHash,
    hash,
  };
}

function isExternalReference(reference) {
  return /^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(reference);
}

function anchorExists(source, rawHash) {
  let anchor;
  try {
    anchor = decodeURIComponent(rawHash);
  } catch {
    return false;
  }
  const escaped = anchor.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return new RegExp(`\\b(?:id|name)\\s*=\\s*["']${escaped}["']`, "i").test(source);
}

function normalizeLocalTarget(sourceFile, referencePath) {
  let decoded;
  try {
    decoded = decodeURIComponent(referencePath);
  } catch {
    errors.push(`${sourceFile}: invalid URL encoding in ${referencePath}`);
    return null;
  }
  const normalized = posix.normalize(posix.join(dirname(sourceFile), decoded));
  if (normalized === ".." || normalized.startsWith("../")) {
    errors.push(`${sourceFile}: reference escapes website/: ${referencePath}`);
    return null;
  }
  return normalized.endsWith("/") ? `${normalized}index.html` : normalized;
}

function validateProjectPath(sourceFile, reference) {
  try {
    const sourceUrl = new URL(posix.join(projectBase, sourceFile), "https://example.invalid");
    const targetUrl = new URL(reference, sourceUrl);
    if (targetUrl.origin === sourceUrl.origin && !targetUrl.pathname.startsWith(projectBase)) {
      errors.push(`${sourceFile}: reference leaves ${projectBase}: ${reference}`);
    }
  } catch {
    errors.push(`${sourceFile}: invalid reference: ${reference}`);
  }
}

const files = await collectFiles(websiteRoot);
const fileSet = new Set(files);
// gradle.properties is not tracked here, so the build version is only readable on
// machines that carry it. Without it, pin the pages to each other instead: a partial
// version bump across the six pages is the drift this gate actually has to catch.
const gradleProperties = await readFile(resolve(repositoryRoot, "gradle.properties"), "utf8").catch(() => "");
const repositoryVersion = gradleProperties.match(/^version=(.+)$/m)?.[1]?.trim();
const declaredVersions = new Map();
for (const page of requiredPages) {
  if (!fileSet.has(page)) errors.push(`missing required page: ${page}`);
}

const sourceCache = new Map();
async function sourceFor(file) {
  if (!sourceCache.has(file)) sourceCache.set(file, await readFile(resolve(websiteRoot, file), "utf8"));
  return sourceCache.get(file);
}

let referenceCount = 0;
let anchorCount = 0;
for (const file of files) {
  const extension = extname(file).toLowerCase();
  if (extension !== ".html" && extension !== ".css") continue;
  const source = await sourceFor(file);

  if (extension === ".html") {
    if (!/<html\b[^>]*\blang=["'][^"']+["']/i.test(source)) errors.push(`${file}: missing html lang attribute`);
    if (!/<meta\b[^>]*\bname=["']viewport["']/i.test(source)) errors.push(`${file}: missing viewport metadata`);
    if (!/<title>[^<]+<\/title>/i.test(source)) errors.push(`${file}: missing document title`);
    if (repositoryVersion && !source.includes(`v${repositoryVersion}`)) {
      errors.push(`${file}: does not identify the current plugin version v${repositoryVersion}`);
    }
    declaredVersions.set(file, new Set([...source.matchAll(/\bv(\d+\.\d+\.\d+(?:\.\d+)?)/g)].map((match) => match[1])));
  }

  const references = extension === ".html" ? extractAttributeReferences(source) : extractCssReferences(source);
  for (const reference of references) {
    if (!reference || isExternalReference(reference) || reference.startsWith("data:")) continue;
    referenceCount += 1;
    if (reference.startsWith("/")) {
      errors.push(`${file}: root-relative reference breaks project Pages paths: ${reference}`);
      continue;
    }
    validateProjectPath(file, reference);

    const { path: referencePath, hash } = splitReference(reference);
    const target = referencePath ? normalizeLocalTarget(file, referencePath) : file;
    if (!target) continue;
    if (!fileSet.has(target)) {
      errors.push(`${file}: missing local target for ${reference}: ${target}`);
      continue;
    }
    if (hash) {
      anchorCount += 1;
      if (extname(target).toLowerCase() !== ".html") {
        errors.push(`${file}: anchor targets a non-HTML file: ${reference}`);
      } else if (!anchorExists(await sourceFor(target), hash)) {
        errors.push(`${file}: missing anchor for ${reference}`);
      }
    }
  }
}

if (!repositoryVersion) {
  const union = new Set([...declaredVersions.values()].flatMap((versions) => [...versions]));
  if (union.size === 0) errors.push("no page declares a version string");
  else if (union.size > 1) errors.push(`pages disagree on version: ${[...union].sort().join(", ")}`);
  else {
    for (const [file, versions] of declaredVersions) {
      if (versions.size === 0) errors.push(`${file}: missing version v${[...union][0]}`);
    }
  }
}

for (const file of files) {
  const stats = await lstat(resolve(websiteRoot, file));
  if (stats.size === 0) errors.push(`${file}: file is empty`);
}

if (errors.length > 0) {
  console.error(`Website validation failed with ${errors.length} issue${errors.length === 1 ? "" : "s"}:`);
  for (const error of errors) console.error(`- ${error}`);
  process.exitCode = 1;
} else {
  const htmlCount = files.filter((file) => extname(file).toLowerCase() === ".html").length;
  console.log(`Website validation passed: ${htmlCount} HTML pages, ${files.length} files, ${referenceCount} local references, ${anchorCount} anchors.`);
}
