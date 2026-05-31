/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // UKM brand palette — maroon/red + supporting neutrals
        ukm: {
          50: '#fdf2f2',
          100: '#fbe5e5',
          200: '#f5bdbd',
          300: '#ec8d8d',
          400: '#dd5555',
          500: '#c12d2d',
          600: '#a31f1f',
          700: '#8b1a1a', // primary maroon
          800: '#6f1717',
          900: '#5a1414',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
};
