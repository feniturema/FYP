import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { orderApi, productApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';
import type { Product } from '../../types';
import Spinner from '../../components/common/Spinner';
import { rm } from '../../utils/format';
import ReviewsPanel from './ReviewsPanel';

export default function ProductDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const cartAdd = useCartStore((s) => s.add);
  const [product, setProduct] = useState<Product | null>(null);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  useEffect(() => {
    productApi.get(Number(id)).then(setProduct).finally(() => setLoading(false));
  }, [id]);

  const buy = async () => {
    if (!isAuthed) { navigate('/login'); return; }
    setMsg(null);
    try {
      const order = await orderApi.create({ sourceType: 'B2C_PRODUCT', refId: Number(id), paymentMethod: 'FAKE_WALLET' });
      navigate(`/orders/${order.id}`);
    } catch (err: any) {
      setMsg({ type: 'error', text: err.response?.data?.message ?? 'Purchase failed.' });
    }
  };

  if (loading) return <Spinner />;
  if (!product) return <p className="text-sm" style={{ color: 'var(--text-faint)' }}>Product not found.</p>;

  return (
    <div className="space-y-8">
      <section className="grid gap-8 md:grid-cols-[360px_1fr]">
        {/* Image */}
        <div
          className="aspect-square overflow-hidden"
          style={{ background: 'var(--surface-inset)', borderRadius: 12 }}
        >
          {product.imageUrl ? (
            <img src={product.imageUrl} alt={product.name} className="h-full w-full object-cover" />
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
              <span className="app-badge badge-brand">Official Store</span>
              {product.category && (
                <span className="app-badge badge-muted">{product.category}</span>
              )}
            </div>
            <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight leading-tight">
              {product.name}
            </h1>
          </div>

          <div className="font-display text-4xl font-extrabold" style={{ color: 'var(--brand)' }}>
            {rm(product.price)}
          </div>

          {product.description && (
            <p className="text-sm leading-relaxed" style={{ color: 'var(--text-dim)' }}>
              {product.description}
            </p>
          )}

          <div
            className="app-panel px-4 py-3 flex items-center justify-between"
            style={{ background: 'var(--surface-raised)' }}
          >
            <span className="app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-faint)' }}>
              Stock remaining
            </span>
            <span
              className="app-mono text-[11px] font-bold"
              style={{ color: product.totalStock > 0 ? '#10b981' : 'var(--signal)' }}
            >
              {product.totalStock > 0 ? `${product.totalStock} units` : 'Sold out'}
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
              disabled={product.totalStock <= 0}
              className="btn btn-brand flex-1 justify-center py-3"
            >
              {product.totalStock <= 0 ? 'Sold out' : 'Buy now →'}
            </button>
            {product.totalStock > 0 && (
              <button
                onClick={() => {
                  if (!isAuthed) { navigate('/login'); return; }
                  cartAdd({ refId: product.id, sourceType: 'B2C_PRODUCT', title: product.name, price: product.price });
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

      <ReviewsPanel targetType="PRODUCT" targetRefId={product.id} />
    </div>
  );
}
