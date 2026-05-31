import { useEffect, useState } from 'react';
import { adminApi, productApi } from '../../services/api';
import type { Product, SeckillEvent } from '../../types';
import Button from '../../components/common/Button';
import Input from '../../components/common/Input';
import { rm } from '../../utils/format';

export default function AdminDashboard() {
  const [products, setProducts] = useState<Product[]>([]);
  const [events, setEvents] = useState<SeckillEvent[]>([]);
  const [msg, setMsg] = useState('');

  const [pForm, setPForm] = useState({ name: '', price: '', totalStock: '', category: '', imageUrl: '', description: '' });
  const [sForm, setSForm] = useState({ productId: '', seckillPrice: '', seckillStock: '', startTime: '', endTime: '' });
  const [editingEventId, setEditingEventId] = useState<number | null>(null);

  const loadProducts = () => productApi.list().then(setProducts);
  const loadEvents = () => adminApi.listSeckill().then(setEvents);
  useEffect(() => { loadProducts(); loadEvents(); }, []);

  const createProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    setMsg('');
    try {
      await adminApi.createProduct({
        name: pForm.name, description: pForm.description, price: Number(pForm.price),
        totalStock: Number(pForm.totalStock), category: pForm.category, imageUrl: pForm.imageUrl,
      });
      setMsg('Product created.');
      setPForm({ name: '', price: '', totalStock: '', category: '', imageUrl: '', description: '' });
      loadProducts();
    } catch (err: any) {
      setMsg(err.response?.data?.message ?? 'Failed to create product.');
    }
  };

  const createSeckill = async (e: React.FormEvent) => {
    e.preventDefault();
    setMsg('');
    try {
      await adminApi.createSeckill({
        productId: Number(sForm.productId),
        seckillPrice: Number(sForm.seckillPrice),
        seckillStock: Number(sForm.seckillStock),
        startTime: new Date(sForm.startTime).toISOString(),
        endTime: new Date(sForm.endTime).toISOString(),
      });
      setMsg('SecKill event scheduled.');
      setSForm({ productId: '', seckillPrice: '', seckillStock: '', startTime: '', endTime: '' });
      loadEvents();
    } catch (err: any) {
      setMsg(err.response?.data?.message ?? 'Failed to create SecKill event.');
    }
  };

  const editEvent = (event: SeckillEvent) => {
    setEditingEventId(event.id);
    setSForm({
      productId: String(event.productId),
      seckillPrice: String(event.seckillPrice),
      seckillStock: String(event.seckillStock),
      startTime: event.startTime.slice(0, 16),
      endTime: event.endTime.slice(0, 16),
    });
  };

  const updateEvent = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingEventId) return;
    setMsg('');
    try {
      await adminApi.updateSeckill(editingEventId, {
        seckillPrice: Number(sForm.seckillPrice),
        seckillStock: Number(sForm.seckillStock),
        startTime: new Date(sForm.startTime).toISOString(),
        endTime: new Date(sForm.endTime).toISOString(),
      });
      setMsg('SecKill event updated.');
      setEditingEventId(null);
      setSForm({ productId: '', seckillPrice: '', seckillStock: '', startTime: '', endTime: '' });
      loadEvents();
    } catch (err: any) {
      setMsg(err.response?.data?.message ?? 'Failed to update SecKill event.');
    }
  };

  const deleteEvent = async (id: number) => {
    setMsg('');
    try {
      await adminApi.deleteSeckill(id);
      setMsg('SecKill event deleted.');
      loadEvents();
    } catch (err: any) {
      setMsg(err.response?.data?.message ?? 'Failed to delete SecKill event.');
    }
  };

  return (
    <div className="space-y-8">
      <h1 className="text-2xl font-extrabold text-ukm-700">Admin Dashboard</h1>
      {msg && <div className="rounded-lg bg-ukm-50 px-4 py-2 text-sm text-ukm-800">{msg}</div>}

      <div className="grid gap-6 lg:grid-cols-2">
        {/* Create product */}
        <form onSubmit={createProduct} className="space-y-3 rounded-xl border border-gray-200 bg-white p-5">
          <h2 className="font-bold text-gray-800">New official product</h2>
          <Input label="Name" value={pForm.name} onChange={(e) => setPForm({ ...pForm, name: e.target.value })} required />
          <Input label="Price (RM)" type="number" step="0.01" value={pForm.price}
            onChange={(e) => setPForm({ ...pForm, price: e.target.value })} required />
          <Input label="Total stock" type="number" value={pForm.totalStock}
            onChange={(e) => setPForm({ ...pForm, totalStock: e.target.value })} required />
          <Input label="Category" value={pForm.category} onChange={(e) => setPForm({ ...pForm, category: e.target.value })} />
          <Input label="Image URL" value={pForm.imageUrl} onChange={(e) => setPForm({ ...pForm, imageUrl: e.target.value })} />
          <Button type="submit" full>Create product</Button>
        </form>

        {/* Create seckill */}
        <form onSubmit={editingEventId ? updateEvent : createSeckill} className="space-y-3 rounded-xl border border-gray-200 bg-white p-5">
          <h2 className="font-bold text-gray-800">{editingEventId ? `Edit SecKill #${editingEventId}` : 'Schedule SecKill event'}</h2>
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-gray-700">Product</span>
            <select className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
              value={sForm.productId} onChange={(e) => setSForm({ ...sForm, productId: e.target.value })} required disabled={editingEventId !== null}>
              <option value="">Select product…</option>
              {products.map((p) => <option key={p.id} value={p.id}>{p.name} (stock {p.totalStock})</option>)}
            </select>
          </label>
          <Input label="SecKill price (RM)" type="number" step="0.01" value={sForm.seckillPrice}
            onChange={(e) => setSForm({ ...sForm, seckillPrice: e.target.value })} required />
          <Input label="SecKill stock" type="number" value={sForm.seckillStock}
            onChange={(e) => setSForm({ ...sForm, seckillStock: e.target.value })} required />
          <Input label="Start time" type="datetime-local" value={sForm.startTime}
            onChange={(e) => setSForm({ ...sForm, startTime: e.target.value })} required />
          <Input label="End time" type="datetime-local" value={sForm.endTime}
            onChange={(e) => setSForm({ ...sForm, endTime: e.target.value })} required />
          <div className="flex gap-2">
            <Button type="submit" full>{editingEventId ? 'Save SecKill' : 'Schedule SecKill'}</Button>
            {editingEventId && (
              <Button type="button" variant="outline" onClick={() => {
                setEditingEventId(null);
                setSForm({ productId: '', seckillPrice: '', seckillStock: '', startTime: '', endTime: '' });
              }}>Cancel</Button>
            )}
          </div>
        </form>
      </div>

      <section>
        <h2 className="mb-3 font-bold text-gray-800">SecKill Events</h2>
        <div className="overflow-auto rounded-xl border border-gray-200 bg-white">
          <table className="w-full min-w-[760px] text-sm">
            <thead className="bg-gray-50 text-left text-gray-500">
              <tr>
                <th className="px-4 py-2">ID</th>
                <th className="px-4 py-2">Product</th>
                <th className="px-4 py-2">Price</th>
                <th className="px-4 py-2">Stock</th>
                <th className="px-4 py-2">Status</th>
                <th className="px-4 py-2">Actions</th>
              </tr>
            </thead>
            <tbody>
              {events.map((event) => (
                <tr key={event.id} className="border-t border-gray-100">
                  <td className="px-4 py-2">{event.id}</td>
                  <td className="px-4 py-2">{event.productName}</td>
                  <td className="px-4 py-2">{rm(event.seckillPrice)}</td>
                  <td className="px-4 py-2">{event.seckillStock}</td>
                  <td className="px-4 py-2">{event.status}</td>
                  <td className="space-x-2 px-4 py-2">
                    <button className="text-sm font-semibold text-ukm-700 disabled:text-gray-400"
                      disabled={event.status !== 'PENDING'} onClick={() => editEvent(event)}>Edit</button>
                    <button className="text-sm font-semibold text-red-600" onClick={() => deleteEvent(event.id)}>Delete</button>
                  </td>
                </tr>
              ))}
              {events.length === 0 && (
                <tr><td colSpan={6} className="px-4 py-6 text-center text-gray-500">No SecKill events yet.</td></tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      <section>
        <h2 className="mb-3 font-bold text-gray-800">Catalogue</h2>
        <div className="overflow-hidden rounded-xl border border-gray-200 bg-white">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 text-left text-gray-500">
              <tr><th className="px-4 py-2">ID</th><th className="px-4 py-2">Name</th><th className="px-4 py-2">Price</th><th className="px-4 py-2">Stock</th></tr>
            </thead>
            <tbody>
              {products.map((p) => (
                <tr key={p.id} className="border-t border-gray-100">
                  <td className="px-4 py-2">{p.id}</td>
                  <td className="px-4 py-2">{p.name}</td>
                  <td className="px-4 py-2">{rm(p.price)}</td>
                  <td className="px-4 py-2">{p.totalStock}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}
