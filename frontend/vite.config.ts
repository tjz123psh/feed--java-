import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 后端地址可覆盖：VITE_API_TARGET=http://host:port；默认 127.0.0.1:8081
  const apiTarget = env.VITE_API_TARGET || 'http://127.0.0.1:8081'

  return {
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      proxy: {
        '/api': {
          target: apiTarget,
          changeOrigin: true,
          rewrite: (path) => path.replace(/^\/api/, ''),
        },
      },
    },
  }
})
