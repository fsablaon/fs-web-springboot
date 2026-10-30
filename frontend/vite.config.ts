import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    // Packaged into the fs-web-frontend jar as static/** (see frontend/pom.xml)
    outDir: 'target/classes/static',
    emptyOutDir: true,
  },
})
