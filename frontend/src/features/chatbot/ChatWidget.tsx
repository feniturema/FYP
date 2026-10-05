import { useEffect, useRef, useState } from 'react';
import { chatApi } from '../../services/api';
import type { ChatMessage } from '../../types';

const GENERIC_ERROR = 'Sorry, something went wrong. Please try again.';
// Transport-level failures from streamAssistant; anything else is the server's own error text.
const TRANSPORT_ERRORS = new Set(['connection closed', 'network error']);

/** A conversation id matching the backend pattern ^[A-Za-z0-9-]{1,64}$. randomUUID needs a secure context. */
function newConversationId(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
}

export default function ChatWidget() {
  const [open, setOpen] = useState(false);
  const [messages, setMessages] = useState<ChatMessage[]>([
    { role: 'assistant', text: 'Hi! I\'m the FTSM Marketplace assistant. How can I help you shop today?' },
  ]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [conversationId] = useState(newConversationId);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => () => abortRef.current?.abort(), []);

  // Only the last bubble streams: sending is disabled until the current answer finishes.
  const updateLast = (fn: (m: ChatMessage) => ChatMessage) =>
    setMessages((ms) => [...ms.slice(0, -1), fn(ms[ms.length - 1])]);

  const send = async () => {
    const text = input.trim();
    if (!text || busy) return;
    setInput('');
    setMessages((m) => [...m, { role: 'user', text }, { role: 'assistant', text: '', streaming: true }]);
    setBusy(true);
    const ctrl = new AbortController();
    abortRef.current = ctrl;
    await chatApi.stream(text, conversationId, {
      onToken: (t) => updateLast((m) => ({ ...m, text: m.text + t })),
      onDone: () => updateLast((m) => ({ ...m, streaming: false })),
      onError: (msg) => {
        const shown = TRANSPORT_ERRORS.has(msg) || msg.startsWith('HTTP ') ? GENERIC_ERROR : msg;
        updateLast((m) => ({ ...m, text: m.text ? `${m.text}\n\n${shown}` : shown, streaming: false }));
      },
    }, ctrl.signal);
    if (!ctrl.signal.aborted) {
      updateLast((m) => ({ ...m, streaming: false }));
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
                  {m.text || (m.streaming ? '…' : '')}
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
