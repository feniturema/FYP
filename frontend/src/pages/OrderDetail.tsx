import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { orderApi } from '../services/api';
import type { Order } from '../types';
import Spinner from '../components/common/Spinner';
import { formatDateTime, rm } from '../utils/format';

const PAYMENT_METHODS = [
  { value: 'FAKE_WALLET', label: 'Campus Wallet (always succeeds)' },
  { value: 'MOCK_FPX', label: 'FPX Bank Transfer (~90%)' },
];

export default function OrderDetail() {
  const { id } = useParams();
  const [order, setOrder] = useState<Order | null>(null);
  const [method, setMethod] = useState('FAKE_WALLET');
  const [loading, setLoading] = useState(true);
  const [paying, setPaying] = useState(false);
  const [msg, setMsg] = useState('');

  useEffect(() => {
    orderApi.get(Number(id)).then((o) => {
      setOrder(o);
      setMethod(o.paymentMethod || 'FAKE_WALLET');
    }).finally(() => setLoading(false));
  }, [id]);

  const pay = async () => {
    if (!order) return;
    setPaying(true); setMsg('');
    try {
      const updated = await orderApi.pay(order.id, method);
      setOrder(updated);
      setMsg(`Payment ${updated.status}.`);
    } catch (err: any) {
      setMsg(err.response?.data?.message ?? 'Payment failed.');
    } finally {
      setPaying(false);
    }
  };

  if (loading) return <Spinner />;
  if (!order) return <p className="text-sm" style={{ color: 'var(--text-faint)' }}>Order not found.</p>;

  return (
    <div className="max-w-2xl space-y-6">
      <Link
        to="/orders"
        className="app-mono text-[11px] uppercase tracking-[0.16em] flex items-center gap-1.5 transition-colors"
        style={{ color: 'var(--text-faint)' }}
        onMouseEnter={(e) => (e.currentTarget.style.color = 'var(--signal)')}
        onMouseLeave={(e) => (e.currentTarget.style.color = 'var(--text-faint)')}
      >
        ← Back to orders
      </Link>

      <div>
        <span className="app-kicker mb-1 block">Order Detail</span>
        <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight">#{order.id}</h1>
        <p className="mt-0.5 app-mono text-[11px]" style={{ color: 'var(--text-faint)' }}>
          {formatDateTime(order.createdAt)}
        </p>
      </div>

      {/* Details panel */}
      <div className="app-panel overflow-hidden">
        {[
          ['Type', order.sourceType === 'B2C_PRODUCT' ? 'Official store' : 'Student listing'],
          ['Reference', `#${order.refId}`],
          ['Amount', rm(order.amount)],
          ['Status', order.status],
          ['Payment', order.paymentMethod
            ? (PAYMENT_METHODS.find((m) => m.value === order.paymentMethod)?.label.replace(/\s*\(.*\)$/, '') ?? order.paymentMethod)
            : 'Not selected'],
        ].map(([label, value], i) => (
          <div
            key={label}
            className="flex items-center justify-between px-4 py-3.5"
            style={{ borderTop: i > 0 ? '1px solid var(--hair)' : 'none' }}
          >
            <span className="app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-faint)' }}>
              {label}
            </span>
            <span
              className="text-sm font-semibold"
              style={{
                color: label === 'Status'
                  ? (order.status === 'PAID' ? '#10b981' : order.status === 'FAILED' ? 'var(--signal)' : 'var(--text)')
                  : 'var(--text)',
              }}
            >
              {value}
            </span>
          </div>
        ))}
      </div>

      {/* Payment action */}
      {order.status === 'PENDING' && (
        <div className="app-panel p-5 space-y-4">
          <h2 className="font-display text-base font-bold uppercase tracking-tight">Complete Payment</h2>
          <label className="block">
            <span className="app-label mb-1.5 block">Payment Method</span>
            <select
              className="app-input"
              value={method}
              onChange={(e) => setMethod(e.target.value)}
            >
              {PAYMENT_METHODS.map((m) => (
                <option key={m.value} value={m.value}>{m.label}</option>
              ))}
            </select>
          </label>
          <button onClick={pay} disabled={paying} className="btn btn-brand w-full justify-center py-3">
            {paying ? 'Processing…' : 'Pay now →'}
          </button>
        </div>
      )}

      {msg && (
        <p
          className="app-mono text-[11px] uppercase tracking-[0.14em]"
          style={{ color: msg.includes('PAID') ? '#10b981' : 'var(--signal)' }}
        >
          {msg}
        </p>
      )}
    </div>
  );
}
