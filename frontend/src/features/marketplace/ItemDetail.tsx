import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { itemApi, orderApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';
import type { Item } from '../../types';
import Spinner from '../../components/common/Spinner';
import { rm } from '../../utils/format';
import ReviewsPanel from './ReviewsPanel';

export default function ItemDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const cartAdd = useCartStore((s) => s.add);
  const [item, setItem] = useState<Item | null>(null);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    itemApi.get(Number(id)).then(setItem).finally(() => setLoading(false));
  }, [id]);

  const buy = async () => {
    if (!isAuthed) { navigate('/login'); return; }
    setMsg(null);
    try {
      const order = await orderApi.create({ sourceType: 'C2C_ITEM', refId: Number(id), paymentMethod: 'FAKE_WALLET' });
      navigate(`/orders/${order.id}`);
    } catch (err: any) {
      setMsg({ type: 'error', text: err.response?.data?.message ?? 'Purchase failed.' });
    }
  };

  if (loading) return <Spinner />;
  if (!item) return <p className="text-sm" style={{ color: 'var(--text-faint)' }}>Listing not found.</p>;

  return (
    <div className="space-y-8">
      <section className="grid gap-8 md:grid-cols-[360px_1fr]">
        {/* Image */}
        <div
          className="aspect-square overflow-hidden"
          style={{ background: 'var(--surface-inset)', borderRadius: 12 }}
        >
          {item.imageUrl ? (
            <img src={item.imageUrl} alt={item.title} className="h-full w-full object-cover" />
          ) : (
            <div className="flex h-full items-center justify-center">
              <svg width="48" height="48" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1"
                style={{ color: 'var(--text-faint)' }}>
                <rect x="3" y="3" width="18" height="18" rx="2"/>
                <circle cx="8.5" cy="8.5" r="1.5"/>
                <polyline points="21 15 16 10 5 21"/>
              </svg>
            </div>
          )}
        </div>

        {/* Info */}
        <div className="space-y-5">
          <div>
            <div className="mb-2 flex items-center gap-2">
              <span className="app-badge badge-muted">Student Listing · C2C</span>
              {item.condition && (
                <span className="app-badge" style={{ background: 'var(--surface-raised)', color: 'var(--text-dim)', border: '1px solid var(--hair)' }}>
                  {item.condition}
                </span>
              )}
            </div>
            <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight leading-tight">
              {item.title}
            </h1>
            {item.category && (
              <p className="mt-1 app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-faint)' }}>
                {item.category}
              </p>
            )}
          </div>

          <div className="font-display text-4xl font-extrabold" style={{ color: 'var(--brand)' }}>
            {rm(item.price)}
          </div>

          {item.description && (
            <p className="text-sm leading-relaxed" style={{ color: 'var(--text-dim)' }}>
              {item.description}
            </p>
          )}

          <div
            className="app-panel px-4 py-3 flex items-center justify-between"
            style={{ background: 'var(--surface-raised)' }}
          >
            <span className="app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-faint)' }}>
              Availability
            </span>
            <span
              className="app-mono text-[11px] font-bold uppercase"
              style={{ color: item.status === 'ACTIVE' ? '#10b981' : 'var(--text-faint)' }}
            >
              {item.status === 'ACTIVE' ? 'Available' : item.status}
            </span>
          </div>

          {msg && (
            <p className="text-sm" style={{ color: msg.type === 'error' ? 'var(--signal)' : '#10b981' }}>
              {msg.text}
            </p>
          )}

          <div className="flex gap-3">
            <button
              onClick={buy}
              disabled={item.status !== 'ACTIVE'}
              className="btn btn-brand flex-1 justify-center py-3"
            >
              {item.status === 'ACTIVE' ? 'Buy now →' : 'Unavailable'}
            </button>
            {item.status === 'ACTIVE' && (
              <button
                onClick={() => {
                  if (!isAuthed) { navigate('/login'); return; }
                  cartAdd({ refId: item.id, sourceType: 'C2C_ITEM', title: item.title, price: item.price });
                  setMsg({ type: 'success', text: 'Added to cart.' });
                }}
                className="btn btn-outline py-3"
              >
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                  <circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/>
                  <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/>
                </svg>
                Add to cart
              </button>
            )}
          </div>
        </div>
      </section>

      <ReviewsPanel targetType="ITEM" targetRefId={item.id} />
    </div>
  );
}
