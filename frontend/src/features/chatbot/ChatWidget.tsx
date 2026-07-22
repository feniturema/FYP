import { useState, useRef, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { chatApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';
import type { ActionCard, ChatMessage } from '../../types';
import { rm } from '../../utils/format';

// Minimal inline formatting for LLM replies: **bold** and `code` only.
function renderRich(text: string) {
  return text.split(/(\*\*[^*]+\*\*|`[^`]+`)/g).map((part, i) => {
    if (part.startsWith('**') && part.endsWith('**')) return <strong key={i}>{part.slice(2, -2)}</strong>;
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2)
      return <code key={i} className="app-mono text-[12px]">{part.slice(1, -1)}</code>;
    return part;
  });
}

export default function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    {
      role: 'assistant',
      text: 'Hi! I\'m the FTSM AI assistant — ask me to find products, check flash sales, or look up your orders. I call real backend tools.',
    },
  ]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const navigate = useNavigate();
  const cartAdd = useCartStore((s) => s.add);
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (open) bottomRef.current?.scrollIntoView({ behavior: 'smooth' });
  }, [messages, open]);

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
      setMessages((m) => [...m, { role: 'assistant', text: 'Sorry, something went wrong. Make sure the backend is running.' }]);
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
      {/* FAB */}
      <button
        onClick={() => setOpen((o) => !o)}
        aria-label="Open AI assistant"
        className={!open ? 'fab-pulse' : ''}
        style={{
          position: 'fixed', bottom: 20, right: 20, zIndex: 40,
          width: 50, height: 50, borderRadius: 16,
          background: open ? 'var(--ink)' : 'var(--signal)',
          border: 'none', cursor: 'pointer', color: 'white',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          boxShadow: '0 4px 16px rgba(0,0,0,0.18)',
          transition: 'background 150ms, transform 120ms',
          transform: open ? 'scale(0.93)' : 'scale(1)',
        }}
      >
        {open ? (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
            <path d="M18 6 6 18M6 6l12 12"/>
          </svg>
        ) : (
          <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
          </svg>
        )}
      </button>

      {/* Panel */}
      {open && (
        <div
          className="chat-panel"
          style={{
            position: 'fixed', bottom: 78, right: 20, zIndex: 40,
            width: 320, height: 440,
            background: 'white',
            border: '1px solid var(--hair)',
            borderRadius: 20,
            boxShadow: '0 8px 40px rgba(0,0,0,0.14)',
            display: 'flex', flexDirection: 'column',
            overflow: 'hidden',
          }}
        >
          {/* Header */}
          <div
            style={{
              background: 'var(--ink)',
              padding: '10px 14px',
              display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            }}
          >
            <div className="flex items-center gap-2">
              <span className="h-1.5 w-1.5 rounded-full bg-green-400" />
              <span className="app-mono text-[10px] uppercase tracking-[0.18em] text-white">
                FTSM Assistant
              </span>
            </div>
            <span className="app-mono text-[10px]" style={{ color: 'rgba(255,255,255,0.35)' }}>
              AI · Function Calling
            </span>
          </div>

          {/* Messages */}
          <div className="flex-1 overflow-y-auto p-3 space-y-3">
            {messages.map((m, i) => (
              <div key={i} className={`flex flex-col ${m.role === 'user' ? 'items-end' : 'items-start'}`}>
                        <div
                  className="max-w-[85%] px-3 py-2 text-sm whitespace-pre-wrap leading-relaxed"
                  style={{
                    borderRadius: m.role === 'user' ? '16px 16px 4px 16px' : '4px 16px 16px 16px',
                    background: m.role === 'user' ? 'var(--ink)' : 'white',
                    color: m.role === 'user' ? 'white' : 'var(--text)',
                    border: m.role === 'user' ? 'none' : '1px solid var(--hair)',
                    boxShadow: m.role === 'user' ? 'none' : '0 1px 4px rgba(0,0,0,0.06)',
                  }}
                >
                  {m.role === 'assistant' ? renderRich(m.text) : m.text}
                </div>

                {/* Action cards */}
                {m.actions && m.actions.length > 0 && (
                  <div className="mt-2 w-[90%] space-y-2">
                    {m.actions.map((a) => (
                      <div
                        key={`${a.sourceType}-${a.refId}`}
                        style={{
                          border: '1px solid var(--hair)',
                          background: 'white',
                          borderRadius: 12,
                          padding: '8px',
                        }}
                      >
                        <div className="flex items-center gap-2">
                          {a.imageUrl && (
                            <img src={a.imageUrl} alt={a.name}
                              style={{ width: 36, height: 36, objectFit: 'cover', borderRadius: 8, flexShrink: 0 }} />
                          )}
                          <div className="min-w-0 flex-1">
                            <p className="truncate text-xs font-semibold" style={{ color: 'var(--text)' }}>{a.name}</p>
                            <p className="app-mono text-[11px] font-bold" style={{ color: 'var(--signal)' }}>
                              {rm(a.price)}
                            </p>
                          </div>
                        </div>
                        <div className="mt-2 flex gap-1.5">
                          <button
                            onClick={() => addToCart(a)}
                            className="btn btn-brand flex-1 justify-center py-1 text-[10px]"
                          >
                            + Cart
                          </button>
                          <button
                            onClick={() => viewDetail(a)}
                            className="btn btn-outline flex-1 justify-center py-1 text-[10px]"
                          >
                            View
                          </button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>
            ))}

            {busy && (
              <div className="flex items-start gap-2">
                <div
                  className="px-3 py-2.5 flex items-center gap-1.5"
                  style={{
                    background: 'white',
                    border: '1px solid var(--hair)',
                    borderRadius: '4px 16px 16px 16px',
                    boxShadow: '0 1px 4px rgba(0,0,0,0.06)',
                  }}
                >
                  <span className="app-mono text-[10px] mr-1" style={{ color: 'var(--text-faint)' }}>Calling tools</span>
                  <span className="typing-dot" />
                  <span className="typing-dot" />
                  <span className="typing-dot" />
                </div>
              </div>
            )}
            <div ref={bottomRef} />
          </div>

          {/* Input */}
          <div
            style={{
              borderTop: '1px solid var(--hair)',
              padding: '8px',
              display: 'flex', gap: 6,
              background: 'var(--surface-raised)',
            }}
          >
            <input
              className="app-input flex-1 text-xs py-2"
              placeholder="Ask about products, stock, orders…"
              value={input}
              onChange={(e) => setInput(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && send()}
            />
            <button
              onClick={send}
              disabled={busy || !input.trim()}
              className="btn btn-brand py-2 px-3"
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5">
                <path d="M22 2 11 13M22 2 15 22 11 13 2 9l20-7z"/>
              </svg>
            </button>
          </div>
        </div>
      )}
    </>
  );
}
