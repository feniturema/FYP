import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { chatApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';
import type { ActionCard, ChatMessage } from '../../types';

export default function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: 'assistant', text: 'Hi! I\'m the FTSM Marketplace assistant. How can I help you shop today?' },
  ]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const cartAdd = useCartStore((s) => s.add);
  const isAuthed = useAuthStore((s) => s.isAuthenticated());

  const send = async () => {
    const text = input.trim();
    if (!text || busy) return;
    setInput('');
    setMessages((m) => [...m, { role: 'user', text }]);
    setBusy(true);
    try {
      const res = await chatApi.send(text);
      setMessages((m) => [...m, { role: 'assistant', text: res.reply, actions: res.actions }]);
    } catch {
      setMessages((m) => [...m, { role: 'assistant', text: 'Sorry, something went wrong.' }]);
    } finally {
      setBusy(false);
    }
  };

  const addToCart = (a: ActionCard) => {
    if (!isAuthed) { navigate('/login'); return; }
    cartAdd({ refId: a.refId, sourceType: a.sourceType, title: a.name, price: a.price });
  };

  const viewDetail = (a: ActionCard) => {
    navigate(a.sourceType === 'B2C_PRODUCT' ? `/product/${a.refId}` : `/item/${a.refId}`);
    setOpen(false);
  };

  return (
    <>
      <button
        onClick={() => setOpen((o) => !o)}
        className="ui-button ui-button-primary fixed bottom-5 right-5 z-40 h-14 w-14 rounded-full p-0 text-2xl shadow-lift"
        aria-label="Open chat assistant"
      >
        {open ? '×' : '💬'}
      </button>

      {open && (
        <div className="chat-panel fixed bottom-24 right-5 z-40 flex h-[28rem] w-80 flex-col overflow-hidden rounded-lg border border-gray-200 bg-white shadow-lift">
          <div className="bg-ukm-700 px-4 py-3 text-sm font-bold text-white">FTSM Assistant</div>
          <div className="flex-1 space-y-3 overflow-y-auto p-3">
            {messages.map((m, i) => (
              <div key={i} className={`flex flex-col ${m.role === 'user' ? 'items-end' : 'items-start'}`}>
                <span className={`max-w-[80%] whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm ${
                  m.role === 'user' ? 'bg-ukm-700 text-white' : 'bg-gray-100 text-gray-800'
                }`}>
                  {m.text}
                </span>
                {m.actions && m.actions.length > 0 && (
                  <div className="mt-2 w-[90%] space-y-2">
                    {m.actions.map((a) => (
                      <div key={`${a.sourceType}-${a.refId}`}
                        className="rounded-lg border border-gray-200 bg-white p-2 text-xs shadow-sm">
                        <div className="flex items-center gap-2">
                          {a.imageUrl && (
                            <img src={a.imageUrl} alt={a.name}
                              className="h-9 w-9 shrink-0 rounded object-cover" />
                          )}
                          <div className="min-w-0 flex-1">
                            <p className="truncate font-semibold text-gray-800">{a.name}</p>
                            <p className="text-ukm-700">RM{a.price}</p>
                          </div>
                        </div>
                        <div className="mt-2 flex gap-2">
                          <button onClick={() => addToCart(a)}
                            className="ui-button ui-button-primary flex-1 px-2 py-1 text-xs">
                            Add to cart
                          </button>
                          <button onClick={() => viewDetail(a)}
                            className="ui-button flex-1 px-2 py-1 text-xs">
                            View
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}
            {busy && <p className="text-xs text-gray-400">Assistant is typing…</p>}
          </div>
          <div className="flex gap-2 border-t border-gray-200 p-2">
            <input
              className="ui-input flex-1"
              placeholder="Ask about products…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && send()}
            />
            <button onClick={send} disabled={busy}
              className="ui-button ui-button-primary px-3 py-2">
              Send
            </button>
          </div>
        </div>
      )}
    </>
  );
}
