import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// The Play dev server. Only the API and short URLs are sent here, so they look like the same origin as the front end.
const api = process.env.API_ORIGIN ?? "http://localhost:9000";

export default defineConfig({
  plugins: [react()],
  server: {
    // Listen on all interfaces so VS Code port forwarding can reach it from outside the devcontainer (the host browser).
    // The default localhost binds only ::1, and forwarding that connects to 127.0.0.1 may not reach it.
    host: true,
    port: 5173,
    strictPort: true,
    // In the E2E compose, runn connects with the service name front. By default Vite rejects Hosts other than localhost
    allowedHosts: ["front"],
    proxy: {
      "/api": api,
      // Short URLs (/{8 alphanumeric chars}) go to the Play redirect. Same routing as /???????? on CloudFront in production.
      // Vite's own paths (/src/…, /@vite/…, /favicon.svg, etc.) do not match because of their length or symbols.
      "^/[A-Za-z0-9]{8}(\\?.*)?$": api,
    },
  },
});
