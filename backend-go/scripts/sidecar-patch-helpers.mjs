export function splitCompleteLines(pending, chunk) {
  const combined = pending + chunk;
  const lastNewline = combined.lastIndexOf("\n");
  if (lastNewline < 0) {
    return { complete: "", pending: combined };
  }
  return {
    complete: combined.slice(0, lastNewline + 1),
    pending: combined.slice(lastNewline + 1),
  };
}
