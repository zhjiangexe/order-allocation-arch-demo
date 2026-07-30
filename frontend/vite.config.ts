import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * 後端 origin 只出現在這裡。應用程式原始碼一律用 `/api` 開頭的相對路徑，因此瀏覽器
 * 發出的都是同源請求、不觸發 CORS preflight，後端也就不需要任何 CORS 設定——那是
 * 一個只為前端存在、卻有機會被複製到其他 profile 的組態。
 */
const BACKEND_ORIGIN = process.env.ARCHONE_BACKEND_ORIGIN ?? 'http://localhost:28290';

export default defineConfig({
  plugins: [react()],
  server: {
    // host port 一律 2829x。strictPort 是刻意的——被佔用時直接失敗，而不是靜默換到 5174
    // 之類的 port：那會讓「我開的到底是哪一份」變成要去翻 log 才知道。
    port: 28295,
    strictPort: true,
    proxy: {
      '/api': {
        target: BACKEND_ORIGIN,
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    css: true,
  },
});
