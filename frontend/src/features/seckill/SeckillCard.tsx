import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { SeckillEvent } from '../../types';
import { seckillApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { useCountdown } from '../../hooks/useCountdown';
import { rm } from '../../utils/format';

export default function SeckillCard({ event }: { event: SeckillEvent }) {
  const cd = useCountdown(event.startTime, event.endTime);
  const isAuthed = useAuthStore((s) => s.isAuthenticated());
  const navigate = useNavigate();
  const [status, setStatus] = useState<string>('');
  const [busy, setBusy] = useState(false);

  const discount = event.originalPrice
    ? Math.round((1 - event.seckillPrice / event.originalPrice) * 100)
    : null;

  const buy = async () => {
    if (!isAuthed) { navigate('/login'); return; }
    setBusy(true); setStatus('Submitting…');
    try {
      const res = await seckillApi.buy(event.id);
      if (res.result !== 'ACCEPTED' || !res.trackingToken) {
        setStatus(res.message); setBusy(false); return;
      }
      setStatus('Accepted! Confirming order…');
      pollResult(res.trackingToken, 0);
    } catch (err: any) {
      setStatus(err.response?.data?.message ?? 'Failed.'); setBusy(false);
    }
  };

  const pollResult = (token: string, attempt: number) => {
    if (attempt > 15) { setStatus('Still processing — check Orders.'); setBusy(false); return; }
    seckillApi.result(token).then((r) => {
      if (r.orderStatus === 'PAID') { setStatus(`Secured! Order #${r.orderId} confirmed.`); setBusy(false); }
      else if (r.orderStatus === 'FAILED') { setStatus('Payment failed.'); setBusy(false); }
      else setTimeout(() => pollResult(token, attempt + 1), 800);
    });
  };

  const canBuy = cd.phase === 'live' && !busy;

  return (
    <div className="app-card flex flex-col">
      {/* Image */}
      <div className="relative aspect-video overflow-hidden" style={{ background: 'var(--surface-inset)' }}>
        {event.imageUrl ? (
          <img src={event.imageUrl} alt={event.productName}
            className="product-card-image h-full w-full object-cover" />
        ) : (
          <div className="flex h-full items-center justify-center">
            <svg width="40" height="40" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1"
              style={{ color: 'var(--text-faint)' }}>
              <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/>
              <polyline points="21 15 16 10 5 21"/>
            </svg>
          </div>
        )}

        {/* Phase badge */}
        {cd.phase === 'live' && (
          <span className="absolute left-2 top-2 flex items-center gap-1 app-badge badge-signal">
            <span className="h-1.5 w-1.5 rounded-full bg-white animate-pulse" />
            LIVE
          </span>
        )}
        {cd.phase === 'upcoming' && (
          <span className="absolute left-2 top-2 app-badge badge-amber">Upcoming</span>
        )}
        {cd.phase === 'ended' && (
          <span className="absolute left-2 top-2 app-badge badge-muted">Ended</span>
        )}

        {/* Discount badge */}
        {discount && cd.phase !== 'ended' && (
          <span
            className="absolute right-2 top-2 font-display text-sm font-extrabold"
            style={{ color: 'var(--signal)' }}
          >
            -{discount}%
          </span>
        )}
      </div>

      {/* Body */}
      <div className="flex flex-1 flex-col p-4 gap-3">
        <h3 className="font-display text-base font-bold uppercase tracking-tight leading-tight" style={{ color: 'var(--text)' }}>
          {event.productName}
        </h3>

        {/* Price */}
        <div className="flex items-baseline gap-2">
          <span className="font-display text-2xl font-extrabold" style={{ color: 'var(--signal)' }}>
            {rm(event.seckillPrice)}
          </span>
          {event.originalPrice && (
            <span className="text-sm line-through" style={{ color: 'var(--text-faint)' }}>
              {rm(event.originalPrice)}
            </span>
          )}
        </div>

        {/* Countdown */}
        <div
          className="app-panel px-3 py-2.5 flex items-center justify-between"
          style={{ background: 'var(--surface-raised)' }}
        >
          <span className="app-mono text-[10px] uppercase tracking-[0.16em]" style={{ color: 'var(--text-faint)' }}>
            {cd.phase === 'upcoming' ? 'Starts in' : cd.phase === 'live' ? 'Ends in' : 'Sale ended'}
          </span>
          {cd.phase !== 'ended' && (
            <span className="app-mono text-sm font-bold" style={{ color: cd.phase === 'live' ? 'var(--signal)' : 'var(--text)' }}>
              {cd.label}
            </span>
          )}
        </div>

        {/* Buy button */}
        <button
          onClick={buy}
          disabled={!canBuy}
          className="btn btn-brand w-full justify-center py-2.5"
          style={cd.phase === 'ended' ? { background: 'var(--surface-inset)', borderColor: 'var(--hair)', color: 'var(--text-faint)' } : {}}
        >
          {cd.phase === 'upcoming' ? 'Not started yet'
            : cd.phase === 'ended' ? 'Sale ended'
            : busy ? 'Processing…' : 'SecKill now'}
        </button>

        {status && (
          <p
            className="app-mono text-[11px] text-center"
            style={{ color: status.includes('Secured') ? '#10b981' : 'var(--text-dim)' }}
          >
            {status}
          </p>
        )}
      </div>
    </div>
  );
}
