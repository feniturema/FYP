import { useEffect, useState } from 'react';
import { adminApi, productApi } from '../../services/api';
import type { Product, SeckillEvent } from '../../types';
import Input from '../../components/common/Input';
import ImageUpload from '../../components/common/ImageUpload';
import { rm } from '../../utils/format';

export default function AdminDashboard() {
  const [products, setProducts] = useState<Product[]>([]);
  const [events, setEvents] = useState<SeckillEvent[]>([]);
  const [msg, setMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null);

  const [pForm, setPForm] = useState({
    name: '', price: '', totalStock: '', category: '', imageUrl: '', description: '',
  });
  const [sForm, setSForm] = useState({
    productId: '', seckillPrice: '', seckillStock: '', startTime: '', endTime: '',
  });
  const [editingEventId, setEditingEventId] = useState<number | null>(null);

  const loadProducts = () => productApi.list().then(setProducts);
  const loadEvents = () => adminApi.listSeckill().then(setEvents);
  useEffect(() => { loadProducts(); loadEvents(); }, []);

  const notice = (type: 'success' | 'error', text: string) => setMsg({ type, text });

  const createProduct = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await adminApi.createProduct({
        name: pForm.name, description: pForm.description, price: Number(pForm.price),
        totalStock: Number(pForm.totalStock), category: pForm.category, imageUrl: pForm.imageUrl,
      });
      notice('success', 'Product created.');
      setPForm({ name: '', price: '', totalStock: '', category: '', imageUrl: '', description: '' });
      loadProducts();
    } catch (err: any) {
      notice('error', err.response?.data?.message ?? 'Failed to create product.');
    }
  };

  const createSeckill = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await adminApi.createSeckill({
        productId: Number(sForm.productId),
        seckillPrice: Number(sForm.seckillPrice),
        seckillStock: Number(sForm.seckillStock),
        startTime: new Date(sForm.startTime).toISOString(),
        endTime: new Date(sForm.endTime).toISOString(),
      });
      notice('success', 'SecKill event scheduled.');
      setSForm({ productId: '', seckillPrice: '', seckillStock: '', startTime: '', endTime: '' });
      loadEvents();
    } catch (err: any) {
      notice('error', err.response?.data?.message ?? 'Failed to create event.');
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
    try {
      await adminApi.updateSeckill(editingEventId, {
        seckillPrice: Number(sForm.seckillPrice),
        seckillStock: Number(sForm.seckillStock),
        startTime: new Date(sForm.startTime).toISOString(),
        endTime: new Date(sForm.endTime).toISOString(),
      });
      notice('success', 'SecKill event updated.');
      setEditingEventId(null);
      setSForm({ productId: '', seckillPrice: '', seckillStock: '', startTime: '', endTime: '' });
      loadEvents();
    } catch (err: any) {
      notice('error', err.response?.data?.message ?? 'Failed to update event.');
    }
  };

  const deleteEvent = async (id: number) => {
    try {
      await adminApi.deleteSeckill(id);
      notice('success', 'SecKill event deleted.');
      loadEvents();
    } catch (err: any) {
      notice('error', err.response?.data?.message ?? 'Failed to delete event.');
    }
  };

  return (
    <div className="space-y-8">
      <div>
        <span className="app-kicker mb-1 block">Administration</span>
        <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight">Admin Dashboard</h1>
      </div>

      {msg && (
        <div
          className="app-panel px-4 py-2.5 text-sm animate-fade-up"
          style={{
            borderLeft: `3px solid ${msg.type === 'success' ? '#10b981' : 'var(--signal)'}`,
            color: msg.type === 'error' ? 'var(--signal)' : 'var(--text)',
          }}
        >
          {msg.text}
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        {/* Create product */}
        <form onSubmit={createProduct} className="app-panel p-5 space-y-4">
          <h2 className="font-display text-base font-bold uppercase tracking-tight">New Official Product</h2>
          <Input label="Name" value={pForm.name} onChange={(e) => setPForm({ ...pForm, name: e.target.value })} required />
          <div className="grid grid-cols-2 gap-3">
            <Input label="Price (RM)" type="number" step="0.01" value={pForm.price}
              onChange={(e) => setPForm({ ...pForm, price: e.target.value })} required />
            <Input label="Stock" type="number" value={pForm.totalStock}
              onChange={(e) => setPForm({ ...pForm, totalStock: e.target.value })} required />
          </div>
          <Input label="Category" value={pForm.category} onChange={(e) => setPForm({ ...pForm, category: e.target.value })} />
          <ImageUpload label="Product image" value={pForm.imageUrl}
            onChange={(url) => setPForm({ ...pForm, imageUrl: url })} />
          <button type="submit" className="btn btn-brand w-full justify-center py-2.5">Create product</button>
        </form>

        {/* Create / Edit seckill */}
        <form onSubmit={editingEventId ? updateEvent : createSeckill} className="app-panel p-5 space-y-4">
          <h2 className="font-display text-base font-bold uppercase tracking-tight">
            {editingEventId ? `Edit SecKill #${editingEventId}` : 'Schedule SecKill Event'}
          </h2>
          <label className="block">
            <span className="app-label mb-1.5 block">Product</span>
            <select
              className="app-input"
              value={sForm.productId}
              onChange={(e) => setSForm({ ...sForm, productId: e.target.value })}
              required
              disabled={editingEventId !== null}
            >
              <option value="">Select product…</option>
              {products.map((p) => (
                <option key={p.id} value={p.id}>{p.name} (stock {p.totalStock})</option>
              ))}
            </select>
          </label>
          <div className="grid grid-cols-2 gap-3">
            <Input label="SecKill Price (RM)" type="number" step="0.01" value={sForm.seckillPrice}
              onChange={(e) => setSForm({ ...sForm, seckillPrice: e.target.value })} required />
            <Input label="SecKill Stock" type="number" value={sForm.seckillStock}
              onChange={(e) => setSForm({ ...sForm, seckillStock: e.target.value })} required />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Input label="Start Time" type="datetime-local" value={sForm.startTime}
              onChange={(e) => setSForm({ ...sForm, startTime: e.target.value })} required />
            <Input label="End Time" type="datetime-local" value={sForm.endTime}
              onChange={(e) => setSForm({ ...sForm, endTime: e.target.value })} required />
          </div>
          <div className="flex gap-2">
            <button type="submit" className="btn btn-brand flex-1 justify-center py-2.5">
              {editingEventId ? 'Save changes' : 'Schedule event'}
            </button>
            {editingEventId && (
              <button
                type="button"
                className="btn btn-outline py-2.5"
                onClick={() => {
                  setEditingEventId(null);
                  setSForm({ productId: '', seckillPrice: '', seckillStock: '', startTime: '', endTime: '' });
                }}
              >
                Cancel
              </button>
            )}
          </div>
        </form>
      </div>

      {/* SecKill Events Table */}
      <section>
        <div className="app-section-head">
          <h2 className="font-display text-lg font-extrabold uppercase tracking-tight">SecKill Events</h2>
          <span className="app-badge badge-muted">{events.length} events</span>
        </div>
        <div className="app-panel overflow-auto">
          <div
            className="grid px-4 py-3 app-mono text-[10px] uppercase tracking-[0.14em]"
            style={{
              color: 'var(--text-faint)', background: 'var(--surface-raised)',
              borderBottom: '1px solid var(--hair)',
              gridTemplateColumns: '3.5rem 1fr 5.5rem 5rem 4.5rem 5.5rem 6rem',
            }}
          >
            <span>ID</span><span>Product</span><span>Price</span>
            <span>Stock</span><span>Status</span><span></span><span></span>
          </div>
          {events.map((event, i) => (
            <div
              key={event.id}
              className="grid items-center px-4 py-3"
              style={{
                gridTemplateColumns: '3.5rem 1fr 5.5rem 5rem 4.5rem 5.5rem 6rem',
                borderTop: i > 0 ? '1px solid var(--hair)' : 'none',
              }}
            >
              <span className="app-mono text-xs font-bold" style={{ color: 'var(--brand)' }}>#{event.id}</span>
              <span className="text-sm truncate" style={{ color: 'var(--text)' }}>{event.productName}</span>
              <span className="app-mono text-xs font-bold" style={{ color: 'var(--signal)' }}>{rm(event.seckillPrice)}</span>
              <span className="app-mono text-xs" style={{ color: 'var(--text-dim)' }}>{event.seckillStock}</span>
              <span className="app-mono text-[10px] uppercase" style={{ color: 'var(--text-dim)' }}>{event.status}</span>
              <button
                className="btn btn-outline py-1 text-[10px]"
                disabled={event.status !== 'PENDING'}
                onClick={() => editEvent(event)}
              >
                Edit
              </button>
              <button
                className="btn py-1 text-[10px]"
                style={{ color: 'var(--signal)', border: '1px solid var(--hair)' }}
                onClick={() => deleteEvent(event.id)}
              >
                Delete
              </button>
            </div>
          ))}
          {events.length === 0 && (
            <div className="px-4 py-8 text-center app-mono text-[11px] uppercase tracking-[0.14em]"
              style={{ color: 'var(--text-faint)' }}>
              No events scheduled
            </div>
          )}
        </div>
      </section>

      {/* Products table */}
      <section>
        <div className="app-section-head">
          <h2 className="font-display text-lg font-extrabold uppercase tracking-tight">Catalogue</h2>
          <span className="app-badge badge-muted">{products.length} products</span>
        </div>
        <div className="app-panel overflow-hidden">
          <div
            className="grid px-4 py-3 app-mono text-[10px] uppercase tracking-[0.14em]"
            style={{
              color: 'var(--text-faint)', background: 'var(--surface-raised)',
              borderBottom: '1px solid var(--hair)',
              gridTemplateColumns: '3.5rem 1fr 6rem 4rem',
            }}
          >
            <span>ID</span><span>Name</span><span>Price</span><span>Stock</span>
          </div>
          {products.map((p, i) => (
            <div
              key={p.id}
              className="grid items-center px-4 py-3"
              style={{
                gridTemplateColumns: '3.5rem 1fr 6rem 4rem',
                borderTop: i > 0 ? '1px solid var(--hair)' : 'none',
              }}
            >
              <span className="app-mono text-xs" style={{ color: 'var(--text-faint)' }}>{p.id}</span>
              <span className="text-sm font-semibold" style={{ color: 'var(--text)' }}>{p.name}</span>
              <span className="app-mono text-sm font-bold" style={{ color: 'var(--brand)' }}>{rm(p.price)}</span>
              <span className="app-mono text-xs" style={{ color: 'var(--text-dim)' }}>{p.totalStock}</span>
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}
