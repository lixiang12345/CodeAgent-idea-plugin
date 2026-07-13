import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vite";
import { viteSingleFile } from "vite-plugin-singlefile";

export default defineConfig({
  plugins: [svelte(), viteSingleFile()],
  build: {
    target: "chrome120",
    cssMinify: true,
    assetsInlineLimit: 100_000_000,
  },
});
