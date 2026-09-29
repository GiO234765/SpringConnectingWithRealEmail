import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [
    tailwindcss(),
    react({ compiler: true }),
  ],
  // There is a package.json one directory up that also installs dependencies.
  // Without dedupe, a pre-bundled dep there can bind to a second copy of React
  // and every hook call throws "Invalid hook call", leaving a blank page.
  resolve: {
    dedupe: ['react', 'react-dom'],
  },
})