import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { useCartStore } from '../store/useCartStore';
import { orderApi } from '../services/api';
import { useAuthStore } from '../store/useAuthStore';
import { rm } from '../utils/format';
import Button from '../components/common/Button';

const PAYMENT_METHODS = [
  { value: 'FAKE_WALLET', label: 'Campus Wallet (always succeeds)' },
  { value: 'MOCK_FPX', label: 'FPX Bank Transfer (~90% success)' },
];

export default function Cart() {
  const { lines, remove, clear, total } = useCartStore();
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const navigate = useNavigate();
  const [paymentMethod, setPaymentMethod] = useState('FAKE_WALLET');
  const [busy, setBusy] = useState(false);
  const [results, setResults] = useState<{ title: string; status: string }[]>([]);
  const [done, setDone] = useState(false);

  if (!isAuthed) {
    navigate('/login');
    return null;
  }

  const checkout = async () => {
    if (lines.length === 0) return;
    setBusy(true);
    setResults([]);
    const settled: { title: string; status: string }[] = [];
    for (const line of lines) {
      try {
        const order = await orderApi.create({
          sourceType: line.sourceType,
          refId: line.refId,
          paymentMethod,
        });
        settled.push({ title: line.title, status: order.status });
      } catch (err: any) {
        const msg = err.response?.data?.message ?? 'Failed';
        settled.push({ title: line.title, status: `ERROR: ${msg}` });
      }
    }
    setResults(settled);
    clear();
    setDone(true);
    setBusy(false);
  };

  if (done) {
    return (
      <div className="mx-auto max-w-lg space-y-4 rounded-2xl border border-gray-200 bg-white p-8">
        <h1 className="text-xl font-bold text-ukm-700">Checkout complete</h1>
        <ul className="space-y-2">
          {results.map((r, i) => (
            <li key={i} className="flex items-center justify-between text-sm">
              <span className="truncate text-gray-700">{r.title}</span>
              <span className={`ml-4 shrink-0 font-semibold ${r.status === 'PAID' ? 'text-green-600' : 'text-red-600'}`}>
                {r.status}
              </span>
            </li>
          ))}
        </ul>
        <div className="flex gap-3">
          <Link to="/orders"><Button>View orders</Button></Link>
          <Link to="/"><Button variant="outline">Continue shopping</Button></Link>
        </div>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-lg space-y-6">
      <h1 className="text-2xl font-extrabold text-ukm-700">Shopping Cart</h1>

      {lines.length === 0 ? (
        <div className="rounded-2xl border border-gray-200 bg-white p-8 text-center text-gray-500">
          Your cart is empty.{' '}
          <Link to="/" className="text-ukm-700 hover:underline">Browse marketplace</Link>
        </div>
      ) : (
        <>
          <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
            {lines.map((line, i) => (
              <div key={i} className={`flex items-center justify-between px-4 py-3 ${i > 0 ? 'border-t border-gray-100' : ''}`}>
                <div>
                  <p className="text-sm font-semibold text-gray-800">{line.title}</p>
                  <p className="text-xs text-gray-500">{line.sourceType === 'B2C_PRODUCT' ? 'Official store' : 'Student listing'}</p>
                </div>
                <div className="flex items-center gap-4">
                  <span className="font-bold text-ukm-700">{rm(line.price)}</span>
                  <button
                    onClick={() => remove(line.refId, line.sourceType)}
                    className="text-xs text-red-500 hover:underline"
                  >
                    Remove
                  </button>
                </div>
              </div>
            ))}
            <div className="flex items-center justify-between border-t border-gray-200 bg-gray-50 px-4 py-3">
              <span className="text-sm font-semibold text-gray-700">Total</span>
              <span className="text-lg font-extrabold text-ukm-700">{rm(total())}</span>
            </div>
          </div>

          <div className="rounded-xl border border-gray-200 bg-white p-5 space-y-4">
            <label className="block">
              <span className="mb-1 block text-sm font-medium text-gray-700">Payment method</span>
              <select
                className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
                value={paymentMethod}
                onChange={(e) => setPaymentMethod(e.target.value)}
              >
                {PAYMENT_METHODS.map((m) => (
                  <option key={m.value} value={m.value}>{m.label}</option>
                ))}
              </select>
            </label>
            <Button full onClick={checkout} disabled={busy}>
              {busy ? `Placing ${lines.length} order(s)…` : `Place ${lines.length} order(s) · ${rm(total())}`}
            </Button>
            <Button full variant="ghost" onClick={clear} disabled={busy}>Clear cart</Button>
          </div>
        </>
      )}
    </div>
  );
}
