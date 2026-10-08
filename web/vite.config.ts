import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
export default defineConfig({
  plugins: [react()],
  build: {
    // Rollup's effect analysis stalls on the installed React dependency graph.
    // Keep all modules in this local-only scaffold until that optimizer is verified.
    rollupOptions: { treeshake: false },
  },
  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,
    proxy: { "/api": { target: "http://127.0.0.1:8765", changeOrigin: true } },
  },
});
