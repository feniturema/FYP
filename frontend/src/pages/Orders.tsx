import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { orderApi } from '../services/api';
import type { Order } from '../types';
import Spinner from '../components/common/Spinner';
import { rm, formatDateTime } from '../utils/format';

const STATUS_STYLE: Record<string, { color: string; label: string }> = {
  PAID:      { color: '#10b981', label: 'Paid' },
  PENDING:   { color: '#f59e0b', label: 'Pending' },
  FAILED:    { color: 'var(--signal)', label: 'Failed' },
  CANCELLED: { color: 'var(--text-faint)', label: 'Cancelled' },
};

export default function Orders() {
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    orderApi.list().then(setOrders).finally(() => setLoading(false));
  }, []);

  if (loading) return <Spinner />;

  const total = orders.reduce((s, o) => s + Number(o.amount), 0);
  const paid = orders.filter((o) => o.status === 'PAID');

  return (
    <div className="space-y-6">
      {/* Header */}
      <div>
        <span className="app-kicker mb-1 block">Account</span>
        <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight">My Orders</h1>
      </div>

      {/* Stats */}
      {orders.length > 0 && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
          {[
            { label: 'Total orders', value: String(orders.length) },
            { label: 'Completed', value: String(paid.length) },
            { label: 'Total spent', value: rm(paid.reduce((s, o) => s + Number(o.amount), 0)) },
            { label: 'Pending', value: String(orders.filter((o) => o.status === 'PENDING').length) },
          ].map((s) => (
            <div key={s.label} className="app-panel px-4 py-3">
              <div className="app-mono text-[10px] uppercase tracking-[0.16em]" style={{ color: 'var(--text-faint)' }}>
                {s.label}
              </div>
              <div className="mt-1 font-display text-xl font-extrabold" style={{ color: 'var(--text)' }}>
                {s.value}
              </div>
            </div>
          ))}
        </div>
      )}

      {/* Table */}
      {orders.length === 0 ? (
        <div
          className="flex flex-col items-center justify-center py-20"
          style={{ border: '1px dashed var(--hair-strong)', borderRadius: 12 }}
        >
          <p className="app-mono text-[11px] uppercase tracking-[0.16em]" style={{ color: 'var(--text-faint)' }}>
            No orders yet
          </p>
          <Link to="/marketplace" className="btn btn-outline mt-4">Browse marketplace</Link>
        </div>
      ) : (
        <div className="app-panel overflow-hidden">
          {/* Header row */}
          <div
            className="grid px-4 py-3 app-mono text-[10px] uppercase tracking-[0.16em]"
            style={{
              color: 'var(--text-faint)',
              background: 'var(--surface-raised)',
              borderBottom: '1px solid var(--hair)',
              gridTemplateColumns: '5rem 1fr 7rem 6rem 1fr',
            }}
          >
            <span>Order</span>
            <span>Type</span>
            <span>Amount</span>
            <span>Status</span>
            <span>Date</span>
          </div>

          {orders.map((o, i) => {
            const s = STATUS_STYLE[o.status] ?? { color: 'var(--text-faint)', label: o.status };
            return (
              <div
                key={o.id}
                className="spec-row-light grid items-center"
                style={{
                  gridTemplateColumns: '5rem 1fr 7rem 6rem 1fr',
                  gap: 0,
                  borderTop: i === 0 ? 'none' : '1px solid var(--hair)',
                }}
              >
                <Link
                  to={`/orders/${o.id}`}
                  className="app-mono text-xs font-bold transition-colors"
                  style={{ color: 'var(--brand)' }}
                >
                  #{o.id}
                </Link>
                <span className="text-xs" style={{ color: 'var(--text-dim)' }}>
                  {o.sourceType === 'B2C_PRODUCT' ? 'Official store' : 'Student listing'}
                </span>
                <span className="font-display font-bold text-sm" style={{ color: 'var(--text)' }}>
                  {rm(o.amount)}
                </span>
                <span>
                  <span
                    className="app-mono text-[10px] uppercase tracking-[0.12em] font-bold"
                    style={{ color: s.color }}
                  >
                    {s.label}
                  </span>
                </span>
                <span className="app-mono text-[10px]" style={{ color: 'var(--text-faint)' }}>
                  {formatDateTime(o.createdAt)}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
