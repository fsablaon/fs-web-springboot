import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      // Longest prefix first: Vite picks the first matching entry
      '/api/visits': {
        target: 'http://localhost:8081',   // visitor backend
        changeOrigin: true,
      },
      '/api': {
        target: 'http://localhost:8080',   // facade backend
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
