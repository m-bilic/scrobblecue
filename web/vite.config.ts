import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    // In development the website and API share one origin, so the credential cookie just works
    proxy: {
      '/v1': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'node',
  },
})
