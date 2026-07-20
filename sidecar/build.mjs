import { build } from "esbuild";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.dirname(fileURLToPath(import.meta.url));

const common = {
  bundle: true,
  format: "esm",
  platform: "node",
  target: "node22",
  sourcemap: false,
  legalComments: "eof",
  nodePaths: [path.join(root, "node_modules")],
  banner: {
    js: "import { createRequire as __codeAgentCreateRequire } from 'node:module'; const require = __codeAgentCreateRequire(import.meta.url);",
  },
};

await Promise.all([
  build({
    ...common,
    entryPoints: [path.join(root, "src/server.ts")],
    outfile: path.join(root, "dist/server.mjs"),
  }),
  build({
    ...common,
    entryPoints: [path.join(root, "src/mcp-runtime.ts")],
    outfile: path.join(root, "dist/mcp-runtime.mjs"),
  }),
  build({
    ...common,
    entryPoints: [path.join(root, "src/acp-runtime.ts")],
    outfile: path.join(root, "dist/acp-runtime.mjs"),
  }),
]);
