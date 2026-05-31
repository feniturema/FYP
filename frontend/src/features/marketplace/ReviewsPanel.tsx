import { useEffect, useState } from 'react';
import { reviewApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import type { ReviewSummary } from '../../types';
import Button from '../../components/common/Button';

interface Props {
  targetType: 'ITEM' | 'PRODUCT';
  targetRefId: number;
}

export default function ReviewsPanel({ targetType, targetRefId }: Props) {
  const [summary, setSummary] = useState<ReviewSummary | null>(null);
  const [rating, setRating] = useState(5);
  const [comment, setComment] = useState('');
  const [msg, setMsg] = useState('');
  const isAuthed = useAuthStore((s) => s.isAuthenticated());

  const load = () => reviewApi.list(targetType, targetRefId).then(setSummary);
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [targetType, targetRefId]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setMsg('');
    try {
      await reviewApi.create({ targetType, targetRefId, rating, comment });
      setComment('');
      setRating(5);
      setMsg('Review posted.');
      load();
    } catch (err: any) {
      setMsg(err.response?.data?.message ?? 'Could not post review.');
    }
  };

  return (
    <section className="space-y-4">
      <div>
        <h2 className="text-lg font-bold text-gray-800">Reviews</h2>
        <p className="text-sm text-gray-500">
          {summary?.count ? `${summary.averageRating.toFixed(1)} / 5 from ${summary.count} reviews` : 'No reviews yet.'}
        </p>
      </div>

      {isAuthed && (
        <form onSubmit={submit} className="space-y-3 rounded-lg border border-gray-200 bg-white p-4">
          <label className="block">
            <span className="mb-1 block text-sm font-medium text-gray-700">Rating</span>
            <select className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
              value={rating} onChange={(e) => setRating(Number(e.target.value))}>
              {[5, 4, 3, 2, 1].map((n) => <option key={n} value={n}>{n}</option>)}
            </select>
          </label>
          <textarea
            className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm"
            rows={3}
            placeholder="Share your experience"
            value={comment}
            onChange={(e) => setComment(e.target.value)}
          />
          {msg && <p className="text-sm text-gray-600">{msg}</p>}
          <Button type="submit">Post review</Button>
        </form>
      )}

      <div className="space-y-3">
        {summary?.reviews.map((review) => (
          <article key={review.id} className="rounded-lg border border-gray-200 bg-white p-4">
            <div className="text-sm font-semibold text-ukm-700">{review.rating} / 5</div>
            {review.comment && <p className="mt-1 text-sm text-gray-700">{review.comment}</p>}
          </article>
        ))}
      </div>
    </section>
  );
}
