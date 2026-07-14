type MermaidApi = typeof import("mermaid")["default"];

let loader: Promise<MermaidApi> | null = null;

export function loadMermaid(): Promise<MermaidApi> {
  if (!loader) {
    loader = import("mermaid").then(({ default: mermaid }) => {
      mermaid.initialize({
        startOnLoad: false,
        securityLevel: "strict",
        theme: "dark",
        fontFamily: "Inter, Segoe UI, sans-serif",
      });
      return mermaid;
    });
  }
  return loader;
}
