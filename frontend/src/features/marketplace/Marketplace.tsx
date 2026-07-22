import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { itemApi, productApi, orderApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';
import type { Item, Product } from '../../types';
import ProductCard from './ProductCard';
import Spinner from '../../components/common/Spinner';

const CATEGORIES = ['All', 'Clothing', 'Electronics', 'Books', 'Food', 'Accessories', 'Other'];

export default function Marketplace() {
  const [products, setProducts] = useState<Product[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [q, setQ] = useState('');
  const [smart, setSmart] = useState(false);
  const [category, setCategory] = useState('All');
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState<{ type: 'info' | 'success' | 'error'; msg: string } | null>(null);
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const navigate = useNavigate();
  const cartAdd = useCartStore((s) => s.add);

  const load = async () => {
    setLoading(true);
    setNotice(null);
    try {
      const useSmart = smart && q.trim().length > 0;
      const [p, i] = useSmart
        ? await Promise.all([productApi.smartSearch(q), itemApi.smartSearch(q)])
        : await Promise.all([productApi.list(q), itemApi.list({ q })]);
      setProducts(p);
      setItems(i);
      if (useSmart) setNotice({ type: 'info', msg: 'AI semantic search — results ranked by intent, not just keywords.' });
    } catch {
      setProducts([]); setItems([]);
      setNotice({ type: 'error', msg: 'Marketplace is unavailable. Make sure the backend is running.' });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); /* eslint-disable-next-line */ }, []);

  const buy = async (sourceType: 'B2C_PRODUCT' | 'C2C_ITEM', refId: number, title: string) => {
    if (!isAuthed) { navigate('/login'); return; }
    setNotice(null);
    try {
      const order = await orderApi.create({ sourceType, refId, paymentMethod: 'FAKE_WALLET' });
      setNotice({ type: 'success', msg: `Order #${order.id} placed — status: ${order.status}` });
      load();
    } catch (err: any) {
      setNotice({ type: 'error', msg: err.response?.data?.message ?? 'Purchase failed.' });
    }
  };

  const filteredItems = category === 'All'
    ? items
    : items.filter((i) => i.category?.toLowerCase() === category.toLowerCase());

  const totalListings = products.length + items.length;

  // While a search is active, don't let an empty "Official Store" block sit on top of the
  // results the shopper actually asked for — only show a section when it has matches.
  const searching = q.trim().length > 0;
  const showOfficial = !searching || products.length > 0;
  const showStudent = !searching || filteredItems.length > 0;
  const noMatches = searching && products.length === 0 && filteredItems.length === 0;

  return (
    <div className="space-y-8">

      {/* ─── Page Header ──────────────────────────── */}
      <header>
        <div className="mb-1 flex items-center gap-2">
          <span className="app-kicker">FTSM Campus</span>
          <span style={{ color: 'var(--text-faint)', fontSize: 10 }}>/</span>
          <span className="app-kicker" style={{ color: 'var(--signal)' }}>Marketplace</span>
        </div>
        <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight md:text-4xl">
          Official Store &amp; Student Listings
        </h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-dim)' }}>
          Merch, flash deals, and second-hand finds from the UKM community.
        </p>
      </header>

      {/* ─── Stats Strip ──────────────────────────── */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {[
          { label: 'Total Listings', value: loading ? '—' : String(totalListings), unit: 'items', live: false },
          { label: 'Official Products', value: loading ? '—' : String(products.length), unit: 'SKUs', live: false },
          { label: 'Student Listings', value: loading ? '—' : String(items.length), unit: 'C2C', live: false },
          { label: 'AI Search', value: 'LIVE', unit: 'DeepSeek', live: true },
        ].map((s) => (
          <div key={s.label} className={`stat-card${s.live ? ' stat-card-live' : ''}`}>
            <div className="app-mono text-[10px] uppercase tracking-[0.16em]" style={{ color: 'var(--text-faint)' }}>
              {s.label}
            </div>
            <div className="mt-1.5 flex items-baseline gap-1.5">
              <span className="font-display text-2xl font-extrabold" style={{ color: s.live ? 'var(--signal)' : 'var(--text)' }}>
                {s.value}
              </span>
              <span className="app-mono text-[10px]" style={{ color: 'var(--text-faint)' }}>{s.unit}</span>
            </div>
          </div>
        ))}
      </div>

      {/* ─── Search Bar ───────────────────────────── */}
      <div className="app-panel p-4">
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
          <div className="relative flex-1">
            <svg
              className="absolute left-3 top-1/2 -translate-y-1/2"
              width="14" height="14" viewBox="0 0 24 24" fill="none"
              stroke="currentColor" strokeWidth="2" style={{ color: 'var(--text-faint)' }}
            >
              <circle cx="11" cy="11" r="8"/><path d="m21 21-4.35-4.35"/>
            </svg>
            <input
              className="app-input pl-9"
              placeholder={smart ? 'Describe what you need, e.g. "cheap dorm fan"…' : 'Search products and listings…'}
              value={q}
              onChange={(e) => setQ(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && load()}
            />
          </div>
          <div className="flex items-center gap-3">
            <label className="flex cursor-pointer items-center gap-2 select-none" onClick={() => setSmart((v) => !v)}>
              <div
                className="relative h-4 w-8 rounded-full transition-colors duration-200"
                style={{ background: smart ? 'var(--signal)' : 'var(--surface-inset)' }}
              >
                <div
                  className="absolute top-0.5 h-3 w-3 rounded-full bg-white shadow transition-transform duration-200"
                  style={{ transform: smart ? 'translateX(18px)' : 'translateX(2px)' }}
                />
              </div>
              <span className="app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-dim)' }}>
                AI Search
              </span>
            </label>
            <button onClick={load} className="btn btn-brand py-2 px-4">
              Search
            </button>
          </div>
        </div>
      </div>

      {/* ─── Notice ───────────────────────────────── */}
      {notice && (
        <div
          className="animate-fade-up app-panel px-4 py-2.5 text-sm flex items-center gap-2"
          style={{
            borderLeft: `3px solid ${notice.type === 'success' ? '#10b981' : notice.type === 'error' ? 'var(--signal)' : 'var(--brand)'}`,
            color: notice.type === 'error' ? 'var(--signal)' : 'var(--text)',
          }}
        >
          {notice.msg}
        </div>
      )}

      {loading ? (
        <Spinner />
      ) : (
        <>
          {noMatches && (
            <EmptyState message={`No results for "${q.trim()}".`} />
          )}

          {/* ─── Official Store ──────────────────── */}
          {showOfficial && (
          <section>
            <div className="app-section-head">
              <div>
                <h2 className="font-display text-xl font-extrabold uppercase tracking-tight">
                  Official Store
                </h2>
                <p className="mt-0.5 text-xs" style={{ color: 'var(--text-faint)' }}>
                  UKM FTSM merchandise — guaranteed quality
                </p>
              </div>
              <span className="app-badge badge-brand">{products.length} items</span>
            </div>

            {products.length === 0 ? (
              <EmptyState message="No official products yet." />
            ) : (
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
                {products.map((p) => (
                  <ProductCard
                    key={`p-${p.id}`}
                    title={p.name}
                    price={p.price}
                    imageUrl={p.imageUrl}
                    badge="Official"
                    badgeVariant="brand"
                    subtitle={p.category}
                    disabled={p.totalStock <= 0}
                    buyLabel={p.totalStock <= 0 ? 'Sold out' : 'Buy'}
                    href={`/product/${p.id}`}
                    onBuy={() => buy('B2C_PRODUCT', p.id, p.name)}
                    onAddToCart={p.totalStock > 0 ? () => {
                      if (!isAuthed) { navigate('/login'); return; }
                      cartAdd({ refId: p.id, sourceType: 'B2C_PRODUCT', title: p.name, price: p.price });
                      setNotice({ type: 'success', msg: `"${p.name}" added to cart.` });
                    } : undefined}
                  />
                ))}
              </div>
            )}
          </section>
          )}

          {/* ─── C2C Listings ───────────────────── */}
          {showStudent && (
          <section>
            <div className="app-section-head">
              <div>
                <h2 className="font-display text-xl font-extrabold uppercase tracking-tight">
                  Student Listings
                </h2>
                <p className="mt-0.5 text-xs" style={{ color: 'var(--text-faint)' }}>
                  Second-hand finds from the UKM community
                </p>
              </div>
              <span className="app-badge badge-muted">{items.length} listings</span>
            </div>

            {/* Category filter */}
            <div className="mb-5 flex flex-wrap gap-2">
              {CATEGORIES.map((c) => (
                <button
                  key={c}
                  onClick={() => setCategory(c)}
                  className="app-mono text-[10px] uppercase tracking-[0.14em] px-3 py-1.5 transition-colors duration-150"
                  style={{
                    border: '1px solid',
                    borderColor: category === c ? 'var(--signal)' : 'var(--hair)',
                    color: category === c ? 'var(--signal)' : 'var(--text-faint)',
                    background: category === c ? 'rgba(255,59,33,0.06)' : 'transparent',
                    borderRadius: 8,
                  }}
                >
                  {c}
                </button>
              ))}
            </div>

            {filteredItems.length === 0 ? (
              <EmptyState message={category === 'All' ? 'No student listings yet.' : `No listings in "${category}".`} />
            ) : (
              <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5">
                {filteredItems.map((i) => (
                  <ProductCard
                    key={`i-${i.id}`}
                    title={i.title}
                    price={i.price}
                    imageUrl={i.imageUrl}
                    badge="C2C"
                    badgeVariant="muted"
                    subtitle={i.condition}
                    href={`/item/${i.id}`}
                    onBuy={() => buy('C2C_ITEM', i.id, i.title)}
                    onAddToCart={() => {
                      if (!isAuthed) { navigate('/login'); return; }
                      cartAdd({ refId: i.id, sourceType: 'C2C_ITEM', title: i.title, price: i.price });
                      setNotice({ type: 'success', msg: `"${i.title}" added to cart.` });
                    }}
                  />
                ))}
              </div>
            )}
          </section>
          )}
        </>
      )}
    </div>
  );
}

function EmptyState({ message }: { message: string }) {
  return (
    <div
      className="flex flex-col items-center justify-center py-16"
      style={{ border: '1px dashed var(--hair-strong)', borderRadius: 12 }}
    >
      <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1"
        style={{ color: 'var(--text-faint)', marginBottom: 12 }}>
        <path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/>
      </svg>
      <p className="app-mono text-[11px] uppercase tracking-[0.16em]" style={{ color: 'var(--text-faint)' }}>
        {message}
      </p>
    </div>
  );
}
