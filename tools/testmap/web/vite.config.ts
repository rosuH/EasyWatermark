import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwind from "@tailwindcss/vite";
import { viteSingleFile } from "vite-plugin-singlefile";
import { thirdPartyLicenses } from "./scripts/licenses";
export default defineConfig({
  plugins: [react(), tailwind(), viteSingleFile(), thirdPartyLicenses()],
  build: { target: "es2022", assetsInlineLimit: Infinity },
  server: {
    proxy: {
      "/api": "http://127.0.0.1:8931",
      "/witness": "http://127.0.0.1:8931",
      "/artifacts": "http://127.0.0.1:8931",
      "/artemis-evidence": "http://127.0.0.1:8931",
    },
  },
});
