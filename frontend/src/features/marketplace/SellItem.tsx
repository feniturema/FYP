import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { itemApi } from '../../services/api';
import Button from '../../components/common/Button';
import Input from '../../components/common/Input';

export default function SellItem() {
  const [form, setForm] = useState({
    title: '', description: '', price: '', category: '', condition: 'USED', imageUrl: '',
  });
  const [error, setError] = useState('');
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    try {
      await itemApi.create({ ...form, price: Number(form.price) } as any);
      navigate('/');
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to create listing.');
    }
  };

  return (
    <div className="mx-auto max-w-lg rounded-2xl border border-gray-200 bg-white p-6 shadow-sm">
      <h1 className="mb-4 text-xl font-bold text-ukm-700">List an item for sale</h1>
      <form onSubmit={submit} className="space-y-4">
        <Input label="Title" value={form.title}
          onChange={(e) => setForm({ ...form, title: e.target.value })} required />
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">Description</span>
          <textarea className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-ukm-500 focus:outline-none"
            rows={3} value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })} />
        </label>
        <Input label="Price (RM)" type="number" step="0.01" value={form.price}
          onChange={(e) => setForm({ ...form, price: e.target.value })} required />
        <Input label="Category" value={form.category}
          onChange={(e) => setForm({ ...form, category: e.target.value })} />
        <label className="block">
          <span className="mb-1 block text-sm font-medium text-gray-700">Condition</span>
          <select className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            value={form.condition} onChange={(e) => setForm({ ...form, condition: e.target.value })}>
            <option value="NEW">New</option>
            <option value="LIKE_NEW">Like new</option>
            <option value="USED">Used</option>
          </select>
        </label>
        <Input label="Image URL" value={form.imageUrl}
          onChange={(e) => setForm({ ...form, imageUrl: e.target.value })} />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <Button type="submit" full>Publish listing</Button>
      </form>
    </div>
  );
}
