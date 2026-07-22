import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { useAuthStore } from '../store/useAuthStore';
import { itemApi, orderApi } from '../services/api';
import type { Item, Order } from '../types';

export default function Profile() {
  const { user } = useAuthStore();
  const [items, setItems] = useState<Item[]>([]);
  const [orders, setOrders] = useState<Order[]>([]);
  const [tab, setTab] = useState<'listings' | 'orders'>('listings');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      itemApi.list().catch(() => [] as Item[]),
      orderApi.list().catch(() => [] as Order[]),
    ]).then(([allItems, allOrders]) => {
      setItems(allItems.filter((i) => i.sellerId === user?.userId));
      setOrders(allOrders);
      setLoading(false);
    });
  }, [user?.userId]);

  const initials = user?.name
    ? user.name.split(' ').map((w) => w[0]).join('').toUpperCase().slice(0, 2)
    : '?';

  const stats = [
    { label: 'Listings', value: items.length },
    { label: 'Orders', value: orders.length },
    { label: 'Active', value: items.filter((i) => i.status === 'ACTIVE').length },
    { label: 'Sold', value: items.filter((i) => i.status === 'SOLD').length },
  ];

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      {/* Header */}
      <div className="app-panel p-6 animate-fade-up">
        <div className="flex items-center gap-5">
          {/* Avatar */}
          <div
            className="flex h-16 w-16 shrink-0 items-center justify-center font-display text-xl font-extrabold text-white"
            style={{ background: 'var(--brand)', borderRadius: 12 }}
          >
            {initials}
          </div>
          <div className="flex-1 min-w-0">
            <h1 className="font-display text-2xl font-extrabold uppercase tracking-tight truncate">
              {user?.name}
            </h1>
            <p className="text-sm mt-0.5 truncate" style={{ color: 'var(--text-dim)' }}>
              {user?.email}
            </p>
            <span
              className="app-mono mt-1 inline-block text-[10px] uppercase tracking-[0.14em] px-2 py-0.5"
              style={{
                background: user?.role === 'ADMIN' ? 'rgba(255,59,33,0.08)' : 'var(--surface-raised)',
                color: user?.role === 'ADMIN' ? 'var(--signal)' : 'var(--text-faint)',
                border: '1px solid',
                borderColor: user?.role === 'ADMIN' ? 'rgba(255,59,33,0.2)' : 'var(--hair)',
                borderRadius: 8,
              }}
            >
              {user?.role}
            </span>
          </div>
          {user?.role !== 'ADMIN' && (
            <Link to="/sell" className="btn btn-brand shrink-0 py-2">
              + List item
            </Link>
          )}
        </div>

        {/* Stats */}
        <div className="mt-5 grid grid-cols-4 gap-3">
          {stats.map((s) => (
            <div
              key={s.label}
              className="text-center p-3"
              style={{ background: 'var(--surface-raised)', border: '1px solid var(--hair)', borderRadius: 8 }}
            >
              <p className="font-display text-2xl font-extrabold">{s.value}</p>
              <p className="app-mono text-[10px] uppercase tracking-wider mt-0.5" style={{ color: 'var(--text-faint)' }}>
                {s.label}
              </p>
            </div>
          ))}
        </div>
      </div>

      {/* Tabs */}
      {user?.role !== 'ADMIN' && (
        <div>
          <div className="flex gap-1 mb-4" style={{ borderBottom: '1px solid var(--hair)' }}>
            {(['listings', 'orders'] as const).map((t) => (
              <button
                key={t}
                onClick={() => setTab(t)}
                className="app-mono text-[11px] uppercase tracking-[0.14em] px-4 py-2.5 transition-colors"
                style={{
                  color: tab === t ? 'var(--signal)' : 'var(--text-dim)',
                  marginBottom: -1,
                  background: 'none',
                  border: 'none',
                  borderBottom: tab === t ? '2px solid var(--signal)' : '2px solid transparent',
                  cursor: 'pointer',
                }}
              >
                {t === 'listings' ? `My Listings (${items.length})` : `Orders (${orders.length})`}
              </button>
            ))}
          </div>

          {loading ? (
            <div className="flex justify-center py-12">
              <span className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-current border-t-transparent" style={{ color: 'var(--signal)' }} />
            </div>
          ) : tab === 'listings' ? (
            items.length === 0 ? (
              <div className="app-panel p-8 text-center" style={{ color: 'var(--text-dim)' }}>
                <p className="text-sm">You haven't listed anything yet.</p>
                <Link to="/sell" className="btn btn-brand mt-4 inline-block py-2">
                  List your first item →
                </Link>
              </div>
            ) : (
              <div className="space-y-2">
                {items.map((item) => (
                  <Link
                    key={item.id}
                    to={`/item/${item.id}`}
                    className="app-panel flex items-center gap-4 p-4 hover:shadow-lift transition-shadow"
                  >
                    {item.imageUrl ? (
                      <img
                        src={item.imageUrl}
                        alt={item.title}
                        className="h-14 w-14 shrink-0 object-cover"
                        style={{ borderRadius: 8, border: '1px solid var(--hair)' }}
                      />
                    ) : (
                      <div
                        className="h-14 w-14 shrink-0 flex items-center justify-center app-mono text-xs"
                        style={{ background: 'var(--surface-raised)', border: '1px solid var(--hair)', borderRadius: 8, color: 'var(--text-faint)' }}
                      >
                        IMG
                      </div>
                    )}
                    <div className="flex-1 min-w-0">
                      <p className="font-semibold text-sm truncate">{item.title}</p>
                      <p className="text-xs mt-0.5" style={{ color: 'var(--text-dim)' }}>
                        {item.category} · {item.condition}
                      </p>
                    </div>
                    <div className="text-right shrink-0">
                      <p className="font-display font-bold text-sm">RM {Number(item.price).toFixed(2)}</p>
                      <span
                        className="app-mono text-[10px] uppercase tracking-wider"
                        style={{ color: item.status === 'ACTIVE' ? '#16a34a' : item.status === 'SOLD' ? 'var(--signal)' : 'var(--text-faint)' }}
                      >
                        {item.status}
                      </span>
                    </div>
                  </Link>
                ))}
              </div>
            )
          ) : (
            orders.length === 0 ? (
              <div className="app-panel p-8 text-center" style={{ color: 'var(--text-dim)' }}>
                <p className="text-sm">No orders yet.</p>
                <Link to="/marketplace" className="btn btn-brand mt-4 inline-block py-2">
                  Start shopping →
                </Link>
              </div>
            ) : (
              <div className="space-y-2">
                {orders.map((order) => (
                  <Link
                    key={order.id}
                    to={`/orders/${order.id}`}
                    className="app-panel flex items-center justify-between p-4 hover:shadow-lift transition-shadow"
                  >
                    <div>
                      <p className="font-semibold text-sm">Order #{order.id}</p>
                      <p className="text-xs mt-0.5" style={{ color: 'var(--text-dim)' }}>
                        {order.sourceType} · {new Date(order.createdAt).toLocaleDateString()}
                      </p>
                    </div>
                    <div className="text-right">
                      <p className="font-display font-bold text-sm">RM {Number(order.amount).toFixed(2)}</p>
                      <span
                        className="app-mono text-[10px] uppercase tracking-wider"
                        style={{ color: order.status === 'PAID' ? '#16a34a' : order.status === 'PENDING' ? '#d97706' : 'var(--text-faint)' }}
                      >
                        {order.status}
                      </span>
                    </div>
                  </Link>
                ))}
              </div>
            )
          )}
        </div>
      )}
    </div>
  );
}
