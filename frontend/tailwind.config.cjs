/** @type {import('tailwindcss').Config} */
module.exports = {
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
        // Dark sci-fi palette (landing / future app reskin)
        void: {
          950: '#05050a',
          900: '#0a0a12',
          800: '#11111c',
          700: '#181826',
          600: '#222134',
        },
        neon: {
          violet: '#a855f7',
          purple: '#7c3aed',
          indigo: '#6366f1',
          cyan: '#22d3ee',
          sky: '#38bdf8',
        },
        // Engineering / spec-sheet palette — single sharp accent (UKM-derived signal red)
        ink: {
          950: '#0b0b0c',
          900: '#0f0f10',
          850: '#141416',
          800: '#1a1a1d',
          700: '#26262a',
        },
        bone: {
          DEFAULT: '#ece8df',
          dim: '#a3a097',
          faint: '#6c6a63',
        },
        signal: {
          DEFAULT: '#2563eb',
          deep: '#1d4ed8',
        },
      },
      fontFamily: {
        sans: ['Archivo', 'system-ui', 'sans-serif'],
        body: ['Archivo', 'system-ui', 'sans-serif'],
        display: ['"Bricolage Grotesque"', 'Archivo', 'system-ui', 'sans-serif'],
        mono: ['"JetBrains Mono"', 'ui-monospace', 'monospace'],
      },
      boxShadow: {
        soft: '0 1px 2px rgba(16, 24, 40, 0.06), 0 8px 24px rgba(16, 24, 40, 0.06)',
        lift: '0 10px 30px rgba(16, 24, 40, 0.10)',
        'glow-violet': '0 0 0 1px rgba(168,85,247,0.35), 0 0 40px -8px rgba(168,85,247,0.55)',
        'glow-cyan': '0 0 0 1px rgba(34,211,238,0.30), 0 0 40px -8px rgba(34,211,238,0.5)',
      },
      transitionTimingFunction: {
        'ui-out': 'cubic-bezier(0.23, 1, 0.32, 1)',
        'ui-in-out': 'cubic-bezier(0.77, 0, 0.175, 1)',
        drawer: 'cubic-bezier(0.32, 0.72, 0, 1)',
      },
      animation: {
        'fade-up': 'fade-up 220ms cubic-bezier(0.23, 1, 0.32, 1) both',
        aurora: 'aurora 18s ease-in-out infinite',
        float: 'float 7s ease-in-out infinite',
        shimmer: 'shimmer 2.5s linear infinite',
        'spin-slow': 'spin 22s linear infinite',
        'pulse-glow': 'pulse-glow 3.5s ease-in-out infinite',
        marquee: 'marquee 26s linear infinite',
        blink: 'blink 1.1s steps(1) infinite',
      },
      keyframes: {
        'fade-up': {
          '0%': { opacity: '0', transform: 'translateY(8px) scale(0.98)' },
          '100%': { opacity: '1', transform: 'translateY(0) scale(1)' },
        },
        aurora: {
          '0%, 100%': { transform: 'translate(0, 0) scale(1)', opacity: '0.7' },
          '33%': { transform: 'translate(8%, -6%) scale(1.15)', opacity: '0.9' },
          '66%': { transform: 'translate(-6%, 8%) scale(1.05)', opacity: '0.6' },
        },
        float: {
          '0%, 100%': { transform: 'translateY(0)' },
          '50%': { transform: 'translateY(-14px)' },
        },
        shimmer: {
          '0%': { backgroundPosition: '-200% 0' },
          '100%': { backgroundPosition: '200% 0' },
        },
        'pulse-glow': {
          '0%, 100%': { opacity: '0.55' },
          '50%': { opacity: '1' },
        },
        marquee: {
          '0%': { transform: 'translateX(0)' },
          '100%': { transform: 'translateX(-50%)' },
        },
        blink: {
          '0%, 100%': { opacity: '1' },
          '50%': { opacity: '0' },
        },
      },
    },
  },
  plugins: [],
};
