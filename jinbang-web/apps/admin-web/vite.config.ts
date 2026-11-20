import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// 开发时把 /api 代理到后端（JB_API_BASE，默认 http://localhost:8080）；流式接口需要关闭缓冲
export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': { target: process.env.JB_API_BASE ?? 'http://localhost:8080', changeOrigin: true },
    },
  },
  build: { chunkSizeWarningLimit: 1500 },
})
