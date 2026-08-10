import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";
import { fileURLToPath } from "node:url";

const scriptDirectory = path.dirname(fileURLToPath(import.meta.url));
const script = path.join(scriptDirectory, "patch-sidecar.mjs");
const jar = path.resolve(scriptDirectory, "../../releases/intellij-augment-0.482.3-beta.jar");

test("patches ripgrep streaming JSON parsing idempotently", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "augment-sidecar-patch-"));
  const sidecar = path.join(directory, "index.cjs");
  try {
    const source = execFileSync("unzip", ["-p", jar, "sidecar/index.cjs"], {
      maxBuffer: 16 * 1024 * 1024,
    });
    fs.writeFileSync(sidecar, source);

    execFileSync(process.execPath, [script, sidecar]);
    const patched = fs.readFileSync(sidecar, "utf8");
    assert.match(patched, /function splitCompleteLines\(/);
    assert.match(patched, /ripgrepPending/);
    assert.match(patched, /splitCompleteLines\(ripgrepPending,m\)/);
    assert.match(patched, /processRipgrepOutput\(ripgrepPending,e\)/);
    assert.doesNotMatch(
      patched,
      /const m=g\.toString\(\),b=this\.processRipgrepOutput\(m,e\)/,
    );

    execFileSync(process.execPath, [script, sidecar]);
    assert.equal(fs.readFileSync(sidecar, "utf8"), patched);
  } finally {
    fs.rmSync(directory, { recursive: true, force: true });
  }
});
