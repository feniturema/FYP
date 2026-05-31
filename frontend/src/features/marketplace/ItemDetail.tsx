import { useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { itemApi, orderApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import type { Item } from '../../types';
import Button from '../../components/common/Button';
import Spinner from '../../components/common/Spinner';
import { rm } from '../../utils/format';
import ReviewsPanel from './ReviewsPanel';

export default function ItemDetail() {
  const { id } = useParams();
  const navigate = useNavigate();
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const [item, setItem] = useState<Item | null>(null);
  const [loading, setLoading] = useState(true);
  const [msg, setMsg] = useState('');

  useEffect(() => {
    itemApi.get(Number(id)).then(setItem).finally(() => setLoading(false));
  }, [id]);

  const buy = async () => {
    if (!isAuthed) { navigate('/login'); return; }
    setMsg('');
    try {
      const order = await orderApi.create({ sourceType: 'C2C_ITEM', refId: Number(id), paymentMethod: 'FAKE_WALLET' });
      navigate(`/orders/${order.id}`);
    } catch (err: any) {
      setMsg(err.response?.data?.message ?? 'Purchase failed.');
    }
  };

  if (loading) return <Spinner />;
  if (!item) return <p className="text-sm text-gray-500">Listing not found.</p>;

  return (
    <div className="space-y-8">
      <section className="grid gap-6 md:grid-cols-[320px_1fr]">
        <div className="aspect-square overflow-hidden rounded-lg bg-gray-100">
          {item.imageUrl ? <img src={item.imageUrl} alt={item.title} className="h-full w-full object-cover" /> :
            <div className="flex h-full items-center justify-center text-gray-300">No image</div>}
        </div>
        <div className="space-y-4">
          <div>
            <p className="text-sm font-semibold text-ukm-700">Student Listing</p>
            <h1 className="text-3xl font-extrabold text-gray-900">{item.title}</h1>
            <p className="text-sm text-gray-500">{[item.category, item.condition].filter(Boolean).join(' · ')}</p>
          </div>
          <p className="text-2xl font-bold text-ukm-700">{rm(item.price)}</p>
          <p className="text-sm text-gray-700">{item.description || 'No description provided.'}</p>
          <p className="text-sm text-gray-500">Status: {item.status}</p>
          {msg && <p className="text-sm text-red-600">{msg}</p>}
          <Button onClick={buy} disabled={item.status !== 'ACTIVE'}>
            {item.status === 'ACTIVE' ? 'Buy now' : 'Unavailable'}
          </Button>
        </div>
      </section>
      <ReviewsPanel targetType="ITEM" targetRefId={item.id} />
    </div>
  );
}
