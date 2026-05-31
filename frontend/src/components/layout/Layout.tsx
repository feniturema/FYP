import { Outlet } from 'react-router-dom';
import Navbar from './Navbar';
import ChatWidget from '../../features/chatbot/ChatWidget';

export default function Layout() {
  return (
    <div className="min-h-screen bg-gray-50">
      <Navbar />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
      <ChatWidget />
    </div>
  );
}
