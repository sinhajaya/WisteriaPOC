import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The Spring Boot API runs on :8081 (see wisteria-api/application.yml).
// Proxy /api and the image paths so the SPA can use same-origin URLs.
const API_TARGET = 'http://localhost:8081'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 3000,
    proxy: {
      '/api': API_TARGET,
      '/static': API_TARGET,
      '/catalog': API_TARGET,
    },
  },
})