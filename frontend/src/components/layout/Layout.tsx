import { Outlet } from 'react-router-dom';
import Navbar from './Navbar';
import ChatWidget from '../../features/chatbot/ChatWidget';

export default function Layout() {
  return (
    <div className="min-h-screen" style={{ background: '#faf9f7' }}>
      <Navbar />
      <main className="mx-auto max-w-7xl px-4 py-8">
        <Outlet />
      </main>
      <footer style={{ borderTop: '1px solid var(--hair)', marginTop: '4rem' }}>
        <div
          className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-3 px-5 py-5"
        >
          <span className="app-mono text-[10px] uppercase tracking-[0.18em]" style={{ color: 'var(--text-faint)' }}>
            FTSM Campus Marketplace — FYP 2026
          </span>
          <span className="app-mono text-[10px] uppercase tracking-[0.18em]" style={{ color: 'var(--text-faint)' }}>
            UKM · Redis + Spring Boot + DeepSeek AI
          </span>
        </div>
      </footer>
      <ChatWidget />
    </div>
  );
}
