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

  const buy = async () => {
    if (!isAuthed) { navigate('/login'); return; }
    setBusy(true);
    setStatus('Submitting…');
    try {
      const res = await seckillApi.buy(event.id);
      if (res.result !== 'ACCEPTED' || !res.trackingToken) {
        setStatus(res.message);
        setBusy(false);
        return;
      }
      setStatus('Accepted! Confirming your order…');
      pollResult(res.trackingToken, 0);
    } catch (err: any) {
      setStatus(err.response?.data?.message ?? 'Failed.');
      setBusy(false);
    }
  };

  const pollResult = (token: string, attempt: number) => {
    if (attempt > 15) {
      setStatus('Still processing — check your Orders page shortly.');
      setBusy(false);
      return;
    }
    seckillApi.result(token).then((r) => {
      if (r.orderStatus === 'PAID') {
        setStatus(`🎉 Secured! Order #${r.orderId} confirmed.`);
        setBusy(false);
      } else if (r.orderStatus === 'FAILED') {
        setStatus('Payment failed for your order.');
        setBusy(false);
      } else {
        setTimeout(() => pollResult(token, attempt + 1), 800);
      }
    });
  };

  const canBuy = cd.phase === 'live' && !busy;

  return (
    <div className="overflow-hidden rounded-xl border border-ukm-200 bg-white shadow-sm">
      <div className="aspect-video bg-gray-100">
        {event.imageUrl
          ? <img src={event.imageUrl} alt={event.productName} className="h-full w-full object-cover" />
          : <div className="flex h-full items-center justify-center text-gray-300">No image</div>}
      </div>
      <div className="space-y-2 p-4">
        <h3 className="font-bold text-gray-800">{event.productName}</h3>
        <div className="flex items-baseline gap-2">
          <span className="text-xl font-extrabold text-ukm-700">{rm(event.seckillPrice)}</span>
          {event.originalPrice && (
            <span className="text-sm text-gray-400 line-through">{rm(event.originalPrice)}</span>
          )}
        </div>
        <div className="text-sm">
          {cd.phase === 'upcoming' && <span className="text-amber-600">Starts in {cd.label}</span>}
          {cd.phase === 'live' && <span className="font-semibold text-green-600">LIVE · ends in {cd.label}</span>}
          {cd.phase === 'ended' && <span className="text-gray-400">Ended</span>}
        </div>
        <button onClick={buy} disabled={!canBuy}
          className="w-full rounded-lg bg-ukm-700 py-2 text-sm font-bold text-white hover:bg-ukm-800 disabled:bg-gray-300">
          {cd.phase === 'upcoming' ? 'Not started' : cd.phase === 'ended' ? 'Ended' : busy ? 'Processing…' : 'SecKill now'}
        </button>
        {status && <p className="text-xs text-gray-600">{status}</p>}
      </div>
    </div>
  );
}
