// Vite ka main config file - yahan hum batate hain Vite ko kaun kaunse plugins use karne hain
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  // react() -> React ko samajhne ke liye
  // tailwindcss() -> Tailwind CSS classes ko samajhne ke liye
  plugins: [react(), tailwindcss()],
})