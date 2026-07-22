import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { itemApi } from '../../services/api';
import ImageUpload from '../../components/common/ImageUpload';

const CONDITIONS = [
  { value: 'NEW', label: 'New', desc: 'Unused, original packaging' },
  { value: 'LIKE_NEW', label: 'Like New', desc: 'Used once or twice, no marks' },
  { value: 'USED', label: 'Used', desc: 'Visible signs of use, fully functional' },
];

export default function SellItem() {
  const [form, setForm] = useState({
    title: '', description: '', price: '', category: '', condition: 'USED', imageUrl: '',
  });
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [aiLoading, setAiLoading] = useState(false);
  const [aiFilled, setAiFilled] = useState(false);
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(''); setSubmitting(true);
    try {
      await itemApi.create({ ...form, price: Number(form.price) } as any);
      navigate('/marketplace');
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to create listing.');
    } finally {
      setSubmitting(false);
    }
  };

  const f = (field: string) => (e: React.ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>) =>
    setForm((prev) => ({ ...prev, [field]: e.target.value }));

  const handleImageChange = (url: string) => {
    setForm((prev) => ({ ...prev, imageUrl: url }));
    setAiFilled(false);
  };

  const runAiFill = async () => {
    if (!form.imageUrl) return;
    setAiLoading(true);
    setError('');
    try {
      const draft = await itemApi.draftFromImage(form.imageUrl);
      setForm((prev) => ({
        ...prev,
        title: draft.title || prev.title,
        description: draft.description || prev.description,
        category: draft.category || prev.category,
        condition: draft.condition || prev.condition,
        price: draft.suggestedPrice ? String(draft.suggestedPrice) : prev.price,
      }));
      setAiFilled(true);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'AI fill failed — fill in manually.');
    } finally {
      setAiLoading(false);
    }
  };

  return (
    <div className="mx-auto max-w-xl space-y-6">
      {/* Header */}
      <div>
        <span className="app-kicker mb-1 block">C2C</span>
        <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight">List an item</h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-dim)' }}>
          Sell to fellow UKM students. Your listing goes live immediately after publishing.
        </p>
      </div>

      {/* Form */}
      <div className="app-panel p-6 animate-fade-up">
        <form onSubmit={submit} className="space-y-5">

          {/* Image upload + AI fill button */}
          <div>
            <ImageUpload
              label="Item photo"
              value={form.imageUrl}
              onChange={handleImageChange}
            />
            {form.imageUrl && (
              <button
                type="button"
                onClick={runAiFill}
                disabled={aiLoading}
                className="btn mt-3 w-full justify-center py-2.5 text-sm"
                style={{
                  border: '1px solid',
                  borderColor: aiFilled ? 'var(--signal)' : 'var(--hair)',
                  background: aiFilled ? 'rgba(255,59,33,0.06)' : 'var(--surface-raised)',
                  color: aiFilled ? 'var(--signal)' : 'var(--text)',
                }}
              >
                {aiLoading ? (
                  <span className="flex items-center gap-2">
                    <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-current border-t-transparent" />
                    Analysing photo…
                  </span>
                ) : aiFilled ? (
                  '✓ AI filled — review and edit below'
                ) : (
                  '✦ AI Smart Fill — auto-generate from photo'
                )}
              </button>
            )}
            {aiFilled && (
              <p className="mt-1.5 text-[11px]" style={{ color: 'var(--text-faint)' }}>
                Fields pre-filled by GPT-4o Vision. Review everything before publishing.
              </p>
            )}
          </div>

          <FieldGroup label="Title" hint="Be specific — good titles sell faster">
            <input className="app-input" value={form.title} onChange={f('title')}
              placeholder="e.g. FTSM Hoodie Size M (barely used)" required />
          </FieldGroup>

          <FieldGroup label="Description">
            <textarea
              className="app-input min-h-24 resize-y"
              rows={3}
              value={form.description}
              onChange={f('description')}
              placeholder="Condition details, reason for selling, any defects…"
            />
          </FieldGroup>

          <div className="grid grid-cols-2 gap-4">
            <FieldGroup label="Price (RM)">
              <input className="app-input" type="number" step="0.01" min="0.01"
                value={form.price} onChange={f('price')} placeholder="0.00" required />
            </FieldGroup>
            <FieldGroup label="Category">
              <input className="app-input" value={form.category} onChange={f('category')}
                placeholder="e.g. Clothing, Books…" />
            </FieldGroup>
          </div>

          <FieldGroup label="Condition">
            <div className="grid grid-cols-3 gap-2 mt-1">
              {CONDITIONS.map((c) => (
                <label
                  key={c.value}
                  className="cursor-pointer p-3 text-center transition-colors"
                  style={{
                    border: '1px solid',
                    borderColor: form.condition === c.value ? 'var(--signal)' : 'var(--hair)',
                    background: form.condition === c.value ? 'rgba(255,59,33,0.04)' : 'transparent',
                    borderRadius: 8,
                  }}
                >
                  <input
                    type="radio"
                    name="condition"
                    value={c.value}
                    checked={form.condition === c.value}
                    onChange={f('condition')}
                    className="sr-only"
                  />
                  <p className="text-xs font-bold" style={{ color: form.condition === c.value ? 'var(--signal)' : 'var(--text)' }}>
                    {c.label}
                  </p>
                  <p className="mt-0.5 text-[10px]" style={{ color: 'var(--text-faint)' }}>{c.desc}</p>
                </label>
              ))}
            </div>
          </FieldGroup>

          {error && (
            <p className="text-sm" style={{ color: 'var(--signal)' }}>{error}</p>
          )}

          <button type="submit" disabled={submitting} className="btn btn-brand w-full justify-center py-3">
            {submitting ? 'Publishing…' : 'Publish listing →'}
          </button>
        </form>
      </div>

      {/* Tips */}
      <div className="app-panel p-4" style={{ background: 'var(--surface-raised)' }}>
        <p className="app-mono text-[10px] uppercase tracking-[0.16em] mb-3" style={{ color: 'var(--text-faint)' }}>
          Tips for a fast sale
        </p>
        {[
          'Upload a photo first, then hit AI Smart Fill — it reads the image and fills everything',
          'Be honest about condition — good reviews build trust',
          'Price competitively — check similar listings first',
        ].map((tip, i) => (
          <div key={i} className="spec-row-light text-xs" style={{ color: 'var(--text-dim)' }}>
            <span className="app-mono text-[10px] mr-2" style={{ color: 'var(--signal)' }}>
              {String(i + 1).padStart(2, '0')}
            </span>
            {tip}
          </div>
        ))}
      </div>
    </div>
  );
}

function FieldGroup({ label, hint, children }: { label: string; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <div className="mb-1.5 flex items-baseline justify-between">
        <span className="app-label">{label}</span>
        {hint && <span className="text-[10px]" style={{ color: 'var(--text-faint)' }}>{hint}</span>}
      </div>
      {children}
    </label>
  );
}
