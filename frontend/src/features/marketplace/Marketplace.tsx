import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { itemApi, productApi, orderApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';
import type { Item, Product } from '../../types';
import ProductCard from './ProductCard';
import Spinner from '../../components/common/Spinner';
import Input from '../../components/common/Input';

export default function Marketplace() {
  const [products, setProducts] = useState<Product[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [q, setQ] = useState('');
  const [smart, setSmart] = useState(false);
  const [loading, setLoading] = useState(true);
  const [notice, setNotice] = useState('');
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const navigate = useNavigate();
  const cartAdd = useCartStore((s) => s.add);

  const load = async () => {
    setLoading(true);
    setNotice('');
    try {
      const useSmart = smart && q.trim().length > 0;
      const [p, i] = useSmart
        ? await Promise.all([productApi.smartSearch(q), itemApi.smartSearch(q)])
        : await Promise.all([productApi.list(q), itemApi.list({ q })]);
      setProducts(p);
      setItems(i);
      if (useSmart) setNotice('🔍 Smart search: results ranked by AI for your intent.');
    } catch {
      setProducts([]);
      setItems([]);
      setNotice('Marketplace data is unavailable right now. Please check that the backend is running.');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); /* eslint-disable-next-line */ }, []);

  const buy = async (sourceType: 'B2C_PRODUCT' | 'C2C_ITEM', refId: number) => {
    if (!isAuthed) { navigate('/login'); return; }
    setNotice('');
    try {
      const order = await orderApi.create({ sourceType, refId, paymentMethod: 'FAKE_WALLET' });
      setNotice(`Order #${order.id} ${order.status}.`);
      load();
    } catch (err: any) {
      setNotice(err.response?.data?.message ?? 'Purchase failed.');
    }
  };

  return (
    <div className="space-y-8">
      <header className="ui-panel p-5">
        <div className="grid gap-4 md:grid-cols-[1fr_22rem] md:items-end">
          <div>
            <p className="text-sm font-semibold text-ukm-700">FTSM Campus Marketplace</p>
            <h1 className="mt-1 text-2xl font-extrabold text-gray-900">Official store and student listings</h1>
            <p className="mt-1 text-sm text-gray-500">Browse merch, flash deals, and second-hand finds from the UKM community.</p>
          </div>
          <div className="space-y-2">
            <Input placeholder={smart ? 'Describe what you need, e.g. "cheap dorm fan"…' : 'Search products & listings…'}
              value={q}
              onChange={(e) => setQ(e.target.value)}
              onKeyDown={(e) => e.key === 'Enter' && load()} />
            <label className="flex cursor-pointer items-center gap-2 text-xs text-gray-600">
              <input type="checkbox" checked={smart}
                onChange={(e) => setSmart(e.target.checked)}
                className="h-4 w-4 accent-ukm-700" />
              🔍 Smart search <span className="text-gray-400">(AI ranks by intent)</span>
            </label>
          </div>
        </div>
      </header>

      {notice && <div className="ui-panel animate-fade-up px-4 py-2 text-sm text-ukm-800">{notice}</div>}

      {loading ? <Spinner /> : (
        <>
          <section className="animate-fade-up">
            <h2 className="mb-3 text-lg font-bold text-gray-800">Official Store</h2>
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
              {products.map((p) => (
                <ProductCard key={`p-${p.id}`} title={p.name} price={p.price} imageUrl={p.imageUrl}
                  badge="Official" subtitle={p.category}
                  disabled={p.totalStock <= 0}
                  buyLabel={p.totalStock <= 0 ? 'Sold out' : 'Buy'}
                  href={`/product/${p.id}`}
                  onBuy={() => buy('B2C_PRODUCT', p.id)}
                  onAddToCart={p.totalStock > 0 ? () => {
                    if (!isAuthed) { navigate('/login'); return; }
                    cartAdd({ refId: p.id, sourceType: 'B2C_PRODUCT', title: p.name, price: p.price });
                    setNotice(`Added "${p.name}" to cart.`);
                  } : undefined}
                />
              ))}
              {products.length === 0 && <p className="text-sm text-gray-500">No products yet.</p>}
            </div>
          </section>

          <section className="animate-fade-up [animation-delay:60ms]">
            <h2 className="mb-3 text-lg font-bold text-gray-800">Student Listings (C2C)</h2>
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-4">
              {items.map((i) => (
                <ProductCard key={`i-${i.id}`} title={i.title} price={i.price} imageUrl={i.imageUrl}
                  badge="Second-hand" subtitle={i.condition}
                  href={`/item/${i.id}`}
                  onBuy={() => buy('C2C_ITEM', i.id)}
                  onAddToCart={() => {
                    if (!isAuthed) { navigate('/login'); return; }
                    cartAdd({ refId: i.id, sourceType: 'C2C_ITEM', title: i.title, price: i.price });
                    setNotice(`Added "${i.title}" to cart.`);
                  }}
                />
              ))}
              {items.length === 0 && <p className="text-sm text-gray-500">No listings yet.</p>}
            </div>
          </section>
        </>
      )}
    </div>
  );
}
