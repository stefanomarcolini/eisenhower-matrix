import type { Config } from 'tailwindcss';

export default {
  // Toggle dark mode by adding/removing `dark` class on <html>
  // Preference is persisted via PUT /api/v1/users/me (theme field)
  darkMode: 'class',
  content: [
    './index.html',
    './src/**/*.{ts,tsx}',
  ],
  theme: {
    extend: {},
  },
  plugins: [],
} satisfies Config;
