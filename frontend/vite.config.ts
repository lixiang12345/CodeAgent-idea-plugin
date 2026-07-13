import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vite";
import { viteSingleFile } from "vite-plugin-singlefile";

export default defineConfig({
  plugins: [
    svelte(),
    viteSingleFile({
      removeViteModuleLoader: true,
      deleteInlinedFiles: true,
    }),
  ],
  build: {
    // Plugin host loads this via file:// temp URL. Keep ESM/module so Svelte 5 + Mermaid stay valid.
    // Do not use JBCefBrowser.loadHTML/data URLs for this multi-MB page.
    target: "chrome120",
    cssMinify: true,
    assetsInlineLimit: 100_000_000,
    modulePreload: false,
  },
});
