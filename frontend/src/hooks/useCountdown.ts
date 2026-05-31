import { useEffect, useState } from 'react';

export interface Countdown {
  phase: 'upcoming' | 'live' | 'ended';
  label: string;
  msRemaining: number;
}

/** Drives SecKill button state based on start/end timestamps. */
export function useCountdown(startTime: string, endTime: string): Countdown {
  const compute = (): Countdown => {
    const now = Date.now();
    const start = new Date(startTime).getTime();
    const end = new Date(endTime).getTime();
    if (now < start) {
      return { phase: 'upcoming', label: format(start - now), msRemaining: start - now };
    }
    if (now <= end) {
      return { phase: 'live', label: format(end - now), msRemaining: end - now };
    }
    return { phase: 'ended', label: 'Ended', msRemaining: 0 };
  };

  const [state, setState] = useState<Countdown>(compute);

  useEffect(() => {
    const id = setInterval(() => setState(compute()), 1000);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [startTime, endTime]);

  return state;
}

function format(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const sec = s % 60;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(h)}:${pad(m)}:${pad(sec)}`;
}
