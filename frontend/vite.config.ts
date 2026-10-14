import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 개발 서버가 /api를 스프링(8080)으로 넘긴다. 브라우저 입장에서는 같은 주소라 CORS 설정이 필요 없다
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
