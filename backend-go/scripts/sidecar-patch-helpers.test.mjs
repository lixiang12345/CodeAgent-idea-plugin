import assert from "node:assert/strict";
import test from "node:test";

import { splitCompleteLines } from "./sidecar-patch-helpers.mjs";

test("preserves ripgrep JSON records across every possible chunk boundary", () => {
  const records = [
    { type: "begin", data: { path: { text: "src/main.go" } } },
    { type: "match", data: { lines: { text: "func main() {}\n" }, line_number: 7 } },
    { type: "end", data: { path: { text: "src/main.go" } } },
  ];
  const stream = `${records.map((record) => JSON.stringify(record)).join("\n")}\n`;

  for (let boundary = 1; boundary < stream.length; boundary += 1) {
    let pending = "";
    let complete = "";
    for (const chunk of [stream.slice(0, boundary), stream.slice(boundary)]) {
      const split = splitCompleteLines(pending, chunk);
      complete += split.complete;
      pending = split.pending;
    }

    assert.equal(pending, "", `boundary ${boundary} left an incomplete record`);
    assert.deepEqual(
      complete.trimEnd().split("\n").map((line) => JSON.parse(line)),
      records,
      `boundary ${boundary} corrupted a JSON record`,
    );
  }
});

test("retains an incomplete final line until a later chunk arrives", () => {
  assert.deepEqual(splitCompleteLines("", '{"type":"mat'), {
    complete: "",
    pending: '{"type":"mat',
  });
  assert.deepEqual(splitCompleteLines('{"type":"mat', 'ch"}\n'), {
    complete: '{"type":"match"}\n',
    pending: "",
  });
});
