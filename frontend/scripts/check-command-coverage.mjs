import { readFileSync, readdirSync } from "node:fs";

const componentSources = [
  new URL("../src/App.svelte", import.meta.url),
  ...readdirSync(new URL("../src/lib/", import.meta.url), { withFileTypes: true })
    .filter((entry) => entry.isFile() && entry.name.endsWith(".svelte"))
    .map((entry) => new URL(`../src/lib/${entry.name}`, import.meta.url)),
].map((url) => readFileSync(url, "utf8"));
const protocolSource = readFileSync(new URL("../src/lib/protocol.ts", import.meta.url), "utf8");
const commands = [...new Set(
  componentSources.flatMap((source) => [...source.matchAll(/sendCommand\("([^"]+)"/g)].map((match) => match[1])),
)].sort();
const handlers = new Set(
  [...protocolSource.matchAll(/command\.type\s*===\s*"([^"]+)"/g)].map((match) => match[1]),
);
handlers.add("bootstrap");

const missing = commands.filter((command) => !handlers.has(command));
if (missing.length > 0) {
  throw new Error(`Development host is missing command handlers: ${missing.join(", ")}`);
}

console.log(`development command coverage: ${commands.length}/${commands.length}`);
