import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

// Play の開発サーバ。フロントと同じオリジンに見せるため、API と短縮 URL だけをここへ流す。
const api = process.env.API_ORIGIN ?? "http://localhost:9000";

export default defineConfig({
  plugins: [react()],
  server: {
    // devcontainer の外 (ホストのブラウザ) から VS Code のポート転送で届くように全インタフェースで待ち受ける。
    // 既定の localhost だと ::1 だけになり、127.0.0.1 へ繋ぎに来る転送が届かないことがある。
    host: true,
    port: 5173,
    strictPort: true,
    // E2E 用の compose では runn がサービス名 front で来る。Vite は既定で localhost 以外の Host を弾く
    allowedHosts: ["front"],
    proxy: {
      "/api": api,
      // 短縮 URL (/{英数 8 文字}) は Play のリダイレクトへ。本番の CloudFront の /???????? と同じ振り分け。
      // Vite 自身のパス (/src/…, /@vite/…, /favicon.svg など) は長さか記号で外れる。
      "^/[A-Za-z0-9]{8}(\\?.*)?$": api,
    },
  },
});
