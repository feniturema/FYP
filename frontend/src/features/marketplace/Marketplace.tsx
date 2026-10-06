import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { itemApi, productApi, orderApi, searchApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';
import type { Item, Product } from '../../types';
import ProductCard from './ProductCard';
import Spinner from '../../components/common/Spinner';
import Input from '../../components/common/Input';
import SearchResults from './SearchResults';
import { createSearchController, type SearchState } from './searchController';

export default function Marketplace() {
  const [products, setProducts] = useState<Product[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [q, setQ] = useState('');
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState<SearchState>({ status: 'idle', hits: [] });
  const searchRef = useRef<ReturnType<typeof createSearchController> | null>(null);
  const [notice, setNotice] = useState('');
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const navigate = useNavigate();
  const cartAdd = useCartStore((s) => s.add);

  const load = async () => {
    setLoading(true);
    try {
      // Browse list (unfiltered); typing in the search box shows SearchResults instead.
      const [p, i] = await Promise.all([productApi.list(), itemApi.list()]);
      setProducts(p);
      setItems(i);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { load(); /* eslint-disable-next-line */ }, []);

  useEffect(() => {
    const c = createSearchController((params, signal) => searchApi.search(params, signal), setSearch);
    searchRef.current = c;
    return () => c.dispose();
  }, []);

  const onSearchInput = (value: string) => {
    setQ(value);
    searchRef.current?.setQuery(value);
  };

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
      <header className="rounded-2xl bg-gradient-to-r from-ukm-700 to-ukm-900 p-8 text-white">
        <h1 className="text-3xl font-extrabold">FTSM Campus Marketplace</h1>
        <p className="mt-1 text-ukm-100">Buy official merch, grab flash deals, and trade with fellow students.</p>
        <div className="mt-4 max-w-md">
          <Input placeholder="Search products & listings…" value={q}
            onChange={(e) => onSearchInput(e.target.value)} />
        </div>
      </header>

      {notice && <div className="rounded-lg bg-ukm-50 px-4 py-2 text-sm text-ukm-800">{notice}</div>}

      {q.trim() ? <SearchResults state={search} /> : loading ? <Spinner /> : (
        <>
          <section>
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

          <section>
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
