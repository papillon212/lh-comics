import { defineConfig } from 'vite'

// 개발 중에는 로컬 서버(./gradlew :server:run)로 API를 넘긴다.
export default defineConfig({
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  build: {
    target: 'safari15',
  },
})
