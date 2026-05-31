import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { orderApi, productApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import type { Product } from '../../types';
import Button from '../../components/common/Button';
import Spinner from '../../components/common/Spinner';
import { rm } from '../../utils/format';
import ReviewsPanel from './ReviewsPanel';

export default function ProductDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const [product, setProduct] = useState<Product | null>(null);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');

  useEffect(() => {
    productApi.get(Number(id)).then(setProduct).finally(() => setLoading(false));
  }, [id]);

  const buy = async () => {
    if (!isAuthed) { navigate('/login'); return; }
    setMsg('');
    try {
      const order = await orderApi.create({ sourceType: 'B2C_PRODUCT', refId: Number(id), paymentMethod: 'FAKE_WALLET' });
      navigate(`/orders/${order.id}`);
    } catch (err: any) {
      setMsg(err.response?.data?.message ?? 'Purchase failed.');
    }
  };

  if (loading) return <Spinner />;
  if (!product) return <p className="text-sm text-gray-500">Product not found.</p>;

  return (
    <div className="space-y-8">
      <section className="grid gap-6 md:grid-cols-[320px_1fr]">
        <div className="aspect-square overflow-hidden rounded-lg bg-gray-100">
          {product.imageUrl ? <img src={product.imageUrl} alt={product.name} className="h-full w-full object-cover" /> :
            <div className="flex h-full items-center justify-center text-gray-300">No image</div>}
        </div>
        <div className="space-y-4">
          <div>
            <p className="text-sm font-semibold text-ukm-700">Official Store</p>
            <h1 className="text-3xl font-extrabold text-gray-900">{product.name}</h1>
            {product.category && <p className="text-sm text-gray-500">{product.category}</p>}
          </div>
          <p className="text-2xl font-bold text-ukm-700">{rm(product.price)}</p>
          <p className="text-sm text-gray-700">{product.description || 'No description provided.'}</p>
          <p className="text-sm text-gray-500">Stock: {product.totalStock}</p>
          {msg && <p className="text-sm text-red-600">{msg}</p>}
          <Button onClick={buy} disabled={product.totalStock <= 0}>
            {product.totalStock <= 0 ? 'Sold out' : 'Buy now'}
          </Button>
        </div>
      </section>
      <ReviewsPanel targetType="PRODUCT" targetRefId={product.id} />
    </div>
  );
}
