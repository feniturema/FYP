import { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { orderApi } from '../services/api';
import type { Order } from '../types';
import Button from '../components/common/Button';
import Spinner from '../components/common/Spinner';
import { formatDateTime, rm } from '../utils/format';

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
    setPaying(true);
    setMsg('');
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
  if (!order) return <p className="text-sm text-gray-500">Order not found.</p>;

  return (
    <div className="max-w-2xl space-y-6">
      <Link to="/orders" className="text-sm font-semibold text-ukm-700">Back to orders</Link>
      <div>
        <h1 className="text-2xl font-extrabold text-ukm-700">Order #{order.id}</h1>
        <p className="text-sm text-gray-500">{formatDateTime(order.createdAt)}</p>
      </div>

      <dl className="grid grid-cols-2 gap-4 rounded-lg border border-gray-200 bg-white p-5 text-sm">
        <div><dt className="text-gray-500">Type</dt><dd className="font-semibold">{order.sourceType}</dd></div>
        <div><dt className="text-gray-500">Reference</dt><dd className="font-semibold">#{order.refId}</dd></div>
        <div><dt className="text-gray-500">Amount</dt><dd className="font-semibold">{rm(order.amount)}</dd></div>
        <div><dt className="text-gray-500">Status</dt><dd className="font-semibold">{order.status}</dd></div>
        <div><dt className="text-gray-500">Payment</dt><dd className="font-semibold">{order.paymentMethod || 'Not selected'}</dd></div>
      </dl>

      {order.status === 'PENDING' && (
        <div className="space-y-3 rounded-lg border border-gray-200 bg-white p-5">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-gray-700">Payment method</span>
            <select className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
              value={method} onChange={(e) => setMethod(e.target.value)}>
              <option value="FAKE_WALLET">FAKE_WALLET</option>
              <option value="MOCK_FPX">MOCK_FPX</option>
            </select>
          </label>
          <Button onClick={pay} disabled={paying}>{paying ? 'Paying…' : 'Pay'}</Button>
        </div>
      )}
      {msg && <p className="text-sm text-gray-600">{msg}</p>}
    </div>
  );
}
