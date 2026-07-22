import { useEffect, useState } from 'react';
import { reviewApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import type { ReviewSummary } from '../../types';

interface Props {
  targetType: 'ITEM' | 'PRODUCT';
  targetRefId: number;
}

export default function ReviewsPanel({ targetType, targetRefId }: Props) {
  const [summary, setSummary] = useState<ReviewSummary | null>(null);
  const [rating, setRating] = useState(5);
  const [comment, setComment] = useState('');
  const [msg, setMsg] = useState<{ type: 'success' | 'error'; text: string } | null>(null);
  const isAuthed = useAuthStore((s) => s.isAuthenticated());

  const load = () => reviewApi.list(targetType, targetRefId).then(setSummary);
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [targetType, targetRefId]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setMsg(null);
    try {
      await reviewApi.create({ targetType, targetRefId, rating, comment });
      setComment(''); setRating(5);
      setMsg({ type: 'success', text: 'Review posted.' });
      load();
    } catch (err: any) {
      setMsg({ type: 'error', text: err.response?.data?.message ?? 'Could not post review.' });
    }
  };

  const stars = (n: number) => '★'.repeat(n) + '☆'.repeat(5 - n);

  return (
    <section className="space-y-5">
      <div style={{ borderTop: '1px solid var(--hair)', paddingTop: '1.5rem' }}>
        <h2 className="font-display text-xl font-extrabold uppercase tracking-tight">Reviews</h2>
        <p className="mt-0.5 text-sm" style={{ color: 'var(--text-faint)' }}>
          {summary?.count
            ? `${summary.averageRating.toFixed(1)} / 5.0 · ${summary.count} review${summary.count !== 1 ? 's' : ''}`
            : 'No reviews yet.'}
        </p>
      </div>

      {isAuthed && (
        <form onSubmit={submit} className="app-panel p-4 space-y-3">
          <h3 className="app-mono text-[11px] uppercase tracking-[0.16em]" style={{ color: 'var(--text-faint)' }}>
            Leave a review
          </h3>
          <label className="block">
            <span className="app-label mb-1.5 block">Rating</span>
            <div className="flex gap-1">
              {[1, 2, 3, 4, 5].map((n) => (
                <button
                  key={n}
                  type="button"
                  onClick={() => setRating(n)}
                  className="text-xl transition-colors"
                  style={{ color: n <= rating ? '#f59e0b' : 'var(--hair-strong)', background: 'none', border: 'none', cursor: 'pointer' }}
                >
                  ★
                </button>
              ))}
            </div>
          </label>
          <label className="block">
            <span className="app-label mb-1.5 block">Comment</span>
            <textarea
              className="app-input resize-y"
              rows={3}
              placeholder="Share your experience with this item…"
              value={comment}
              onChange={(e) => setComment(e.target.value)}
            />
          </label>
          {msg && (
            <p className="text-sm" style={{ color: msg.type === 'error' ? 'var(--signal)' : '#10b981' }}>
              {msg.text}
            </p>
          )}
          <button type="submit" className="btn btn-brand py-2">Post review</button>
        </form>
      )}

      <div className="space-y-3">
        {summary?.reviews.map((review) => (
          <article
            key={review.id}
            className="app-panel px-4 py-3 space-y-1"
          >
            <div className="flex items-center justify-between">
              <span style={{ color: '#f59e0b', fontSize: 14, letterSpacing: 1 }}>{stars(review.rating)}</span>
              <span className="app-mono text-[10px]" style={{ color: 'var(--text-faint)' }}>
                {review.rating} / 5
              </span>
            </div>
            {review.comment && (
              <p className="text-sm" style={{ color: 'var(--text-dim)' }}>{review.comment}</p>
            )}
          </article>
        ))}
      </div>
    </section>
  );
}
