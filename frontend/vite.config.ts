import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

/**
 * 後端 origin 只出現在這裡。應用程式原始碼一律用 `/api` 開頭的相對路徑，因此瀏覽器
 * 發出的都是同源請求、不觸發 CORS preflight，後端也就不需要任何 CORS 設定——那是
 * 一個只為前端存在、卻有機會被複製到其他 profile 的組態。
 */
const BACKEND_ORIGIN = process.env.ARCHONE_BACKEND_ORIGIN ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
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
