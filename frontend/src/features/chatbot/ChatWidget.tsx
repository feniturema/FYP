import { useState } from 'react';
import { chatApi } from '../../services/api';
import type { ChatMessage } from '../../types';

export default function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: 'assistant', text: 'Hi! I\'m the FTSM Marketplace assistant. How can I help you shop today?' },
  ]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);

  const send = async () => {
    const text = input.trim();
    if (!text || busy) return;
    setInput('');
    setMessages((m) => [...m, { role: 'user', text }]);
    setBusy(true);
    try {
      const res = await chatApi.send(text);
      setMessages((m) => [...m, { role: 'assistant', text: res.reply }]);
    } catch {
      setMessages((m) => [...m, { role: 'assistant', text: 'Sorry, something went wrong.' }]);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button
        onClick={() => setOpen((o) => !o)}
        className="fixed bottom-5 right-5 z-40 flex h-14 w-14 items-center justify-center rounded-full bg-ukm-700 text-2xl text-white shadow-lg hover:bg-ukm-800"
        aria-label="Open chat assistant"
      >
        {open ? '×' : '💬'}
      </button>

      {open && (
        <div className="fixed bottom-24 right-5 z-40 flex h-[28rem] w-80 flex-col overflow-hidden rounded-2xl border border-gray-200 bg-white shadow-2xl">
          <div className="bg-ukm-700 px-4 py-3 text-sm font-bold text-white">FTSM Assistant</div>
          <div className="flex-1 space-y-3 overflow-y-auto p-3">
            {messages.map((m, i) => (
              <div key={i} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <span className={`max-w-[80%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm ${
                  m.role === 'user' ? 'bg-ukm-700 text-white' : 'bg-gray-100 text-gray-800'
                }`}>
                  {m.text}
                </span>
              </div>
            ))}
            {busy && <p className="text-xs text-gray-400">Assistant is typing…</p>}
          </div>
          <div className="flex gap-2 border-t border-gray-200 p-2">
            <input
              className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-ukm-500 focus:outline-none"
              placeholder="Ask about products…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && send()}
            />
            <button onClick={send} disabled={busy}
              className="rounded-lg bg-ukm-700 px-3 py-2 text-sm font-semibold text-white hover:bg-ukm-800 disabled:opacity-50">
              Send
            </button>
          </div>
        </div>
      )}
    </>
  );
}
