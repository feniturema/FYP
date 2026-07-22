import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useCartStore } from '../store/useCartStore';
import { orderApi } from '../services/api';
import { useAuthStore } from '../store/useAuthStore';
import { rm } from '../utils/format';

const PAYMENT_METHODS = [
  { value: 'FAKE_WALLET', label: 'Campus Wallet', desc: 'Always succeeds — for testing' },
  { value: 'MOCK_FPX', label: 'FPX Bank Transfer', desc: 'Mock bank transfer — demo-safe' },
];

export default function Cart() {
  const { lines, remove, clear, total } = useCartStore();
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const navigate = useNavigate();
  const [paymentMethod, setPaymentMethod] = useState('FAKE_WALLET');
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState<{ title: string; status: string }[]>([]);
  const [done, setDone] = useState(false);

  if (!isAuthed) { navigate('/login'); return null; }

  const checkout = async () => {
    if (lines.length === 0) return;
    setBusy(true); setResults([]);
    const settled: { title: string; status: string }[] = [];
    for (const line of lines) {
      try {
        const order = await orderApi.create({ sourceType: line.sourceType, refId: line.refId, paymentMethod });
        settled.push({ title: line.title, status: order.status });
      } catch (err: any) {
        settled.push({ title: line.title, status: `FAILED: ${err.response?.data?.message ?? 'Error'}` });
      }
    }
    setResults(settled);
    clear();
    setDone(true);
    setBusy(false);
  };

  if (done) {
    const allPaid = results.every((r) => r.status === 'PAID');
    return (
      <div className="mx-auto max-w-xl animate-fade-up">
        <div className="app-panel p-8 space-y-5">
          <div>
            <span className="app-kicker mb-2 block">Checkout complete</span>
            <h1 className="font-display text-2xl font-extrabold uppercase tracking-tight">
              {allPaid ? 'All orders confirmed' : 'Order summary'}
            </h1>
          </div>
          <div style={{ borderTop: '1px solid var(--hair)' }}>
            {results.map((r, i) => (
              <div
                key={i}
                className="flex items-center justify-between py-3"
                style={{ borderBottom: '1px solid var(--hair)' }}
              >
                <span className="text-sm truncate" style={{ color: 'var(--text)', maxWidth: '65%' }}>{r.title}</span>
                <span
                  className="app-mono text-[11px] font-bold"
                  style={{ color: r.status === 'PAID' ? '#10b981' : 'var(--signal)' }}
                >
                  {r.status}
                </span>
              </div>
            ))}
          </div>
          <div className="flex gap-3">
            <Link to="/orders" className="btn btn-brand">View orders</Link>
            <Link to="/marketplace" className="btn btn-outline">Continue shopping</Link>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-2xl space-y-6">
      {/* Header */}
      <div>
        <span className="app-kicker mb-1 block">Shopping</span>
        <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight">Cart</h1>
      </div>

      {lines.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center py-20"
          style={{ border: '1px dashed var(--hair-strong)', borderRadius: 12 }}
        >
          <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1"
            style={{ color: 'var(--text-faint)', marginBottom: 12 }}>
            <circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/>
            <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/>
          </svg>
          <p className="app-mono text-[11px] uppercase tracking-[0.16em]" style={{ color: 'var(--text-faint)' }}>
            Your cart is empty
          </p>
          <Link to="/marketplace" className="btn btn-outline mt-4">Browse marketplace</Link>
        </div>
      ) : (
        <div className="grid gap-5 md:grid-cols-[1fr_280px]">
          {/* Items */}
          <div className="app-panel overflow-hidden">
            <div className="px-4 py-3" style={{ borderBottom: '1px solid var(--hair)', background: 'var(--surface-raised)' }}>
              <span className="app-mono text-[10px] uppercase tracking-[0.18em]" style={{ color: 'var(--text-faint)' }}>
                {lines.length} item{lines.length !== 1 ? 's' : ''}
              </span>
            </div>
            {lines.map((line, i) => (
              <div
                key={i}
                className="flex items-center justify-between px-4 py-3.5"
                style={{ borderBottom: '1px solid var(--hair)' }}
              >
                <div className="min-w-0">
                  <p className="text-sm font-semibold truncate" style={{ color: 'var(--text)' }}>{line.title}</p>
                  <p className="app-mono text-[10px] mt-0.5" style={{ color: 'var(--text-faint)' }}>
                    {line.sourceType === 'B2C_PRODUCT' ? 'Official store' : 'Student listing'}
                  </p>
                </div>
                <div className="flex items-center gap-4 shrink-0 ml-4">
                  <span className="font-display font-bold" style={{ color: 'var(--brand)' }}>{rm(line.price)}</span>
                  <button
                    onClick={() => remove(line.refId, line.sourceType)}
                    className="app-mono text-[10px] uppercase tracking-wider transition-colors"
                    style={{ color: 'var(--text-faint)' }}
                    onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--signal)')}
                    onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--text-faint)')}
                  >
                    Remove
                  </button>
                </div>
              </div>
            ))}
            <div
              className="flex items-center justify-between px-4 py-3"
              style={{ background: 'var(--surface-raised)' }}
            >
              <span className="app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-dim)' }}>Total</span>
              <span className="font-display text-2xl font-extrabold" style={{ color: 'var(--text)' }}>{rm(total())}</span>
            </div>
          </div>

          {/* Checkout panel */}
          <div className="space-y-4">
            <div className="app-panel p-4 space-y-4">
              <h2 className="font-display text-sm font-bold uppercase tracking-tight">Payment Method</h2>
              {PAYMENT_METHODS.map((m) => (
                <label key={m.value} className="flex cursor-pointer items-start gap-3">
                  <input
                    type="radio"
                    name="payment"
                    value={m.value}
                    checked={paymentMethod === m.value}
                    onChange={() => setPaymentMethod(m.value)}
                    className="mt-0.5"
                    style={{ accentColor: 'var(--brand)' }}
                  />
                  <div>
                    <p className="text-sm font-semibold" style={{ color: 'var(--text)' }}>{m.label}</p>
                    <p className="text-xs" style={{ color: 'var(--text-faint)' }}>{m.desc}</p>
                  </div>
                </label>
              ))}
            </div>

            <button
              onClick={checkout}
              disabled={busy}
              className="btn btn-brand w-full justify-center py-3"
            >
              {busy ? `Placing ${lines.length} order(s)…` : `Place ${lines.length} order(s) · ${rm(total())}`}
            </button>
            <button
              onClick={clear}
              disabled={busy}
              className="btn btn-ghost w-full justify-center py-2"
            >
              Clear cart
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
