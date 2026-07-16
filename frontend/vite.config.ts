import { svelte } from "@sveltejs/vite-plugin-svelte";
import { defineConfig } from "vite";

// Match original IDEA plugin packaging: multi-file HTML + assets under a relative base.
// The JVM serves these via a custom JCEF resource handler at http://codeagent.localhost/.
export default defineConfig({
  plugins: [svelte()],
  base: "./",
  server: {
    proxy: {
      "/__contextengine": {
        target: "http://127.0.0.1:8790",
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/__contextengine/, ""),
      },
    },
  },
  build: {
    target: "chrome120",
    cssCodeSplit: true,
    assetsInlineLimit: 4096,
    modulePreload: false,
    outDir: "dist",
    emptyOutDir: true,
    rollupOptions: {
      output: {
        entryFileNames: "assets/[name]-[hash].js",
        chunkFileNames: "assets/[name]-[hash].js",
        assetFileNames: "assets/[name]-[hash][extname]",
      },
    },
  },
});
