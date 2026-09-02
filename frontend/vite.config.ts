// vitest 설정을 함께 담으므로 vite 가 아니라 vitest 쪽 defineConfig 를 쓴다.
// vite 의 것은 test 키를 모른다.
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import path from 'node:path';

/**
 * 개발 서버는 5173 이고 `/api` 로 시작하는 요청만 8080 으로 넘긴다.
 *
 * 프록시를 두는 이유는 편의가 아니라 인증이다. ADR 0006 이 리프레시 토큰을
 * `SameSite=Strict` 쿠키로 정했기 때문에, 브라우저가 보는 출처가 프론트와 백엔드로
 * 갈리면 갱신 요청에 쿠키가 실리지 않는다. 로그인은 되는데 새로고침하면 풀리는,
 * 원인을 찾기 어려운 증상으로 나타난다. 쿠키 `Path` 가 `/api/auth` 인 것도
 * 이 프록시 경로와 맞아야 한다.
 */
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@': path.resolve(__dirname, './src') },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
  },
});
