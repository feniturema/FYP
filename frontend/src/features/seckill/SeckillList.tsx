import { useEffect, useState } from 'react';
import { seckillApi } from '../../services/api';
import type { SeckillEvent } from '../../types';
import SeckillCard from './SeckillCard';
import Spinner from '../../components/common/Spinner';

export default function SeckillList() {
  const [events, setEvents] = useState<SeckillEvent[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    seckillApi.events().then(setEvents).finally(() => setLoading(false));
  }, []);

  const live = events.filter((e) => {
    const now = Date.now();
    return new Date(e.startTime).getTime() <= now && new Date(e.endTime).getTime() > now;
  });
  const upcoming = events.filter((e) => new Date(e.startTime).getTime() > Date.now());
  const ended = events.filter((e) => new Date(e.endTime).getTime() <= Date.now());

  return (
    <div className="space-y-10">
      {/* Header */}
      <header>
        <div className="mb-1 flex items-center gap-2">
          <span className="app-kicker">FTSM Campus</span>
          <span style={{ color: 'var(--text-faint)', fontSize: 10 }}>/</span>
          <span className="app-kicker" style={{ color: 'var(--signal)' }}>Flash Sale</span>
        </div>
        <h1 className="font-display text-3xl font-extrabold uppercase tracking-tight md:text-4xl">
          SecKill — Atomic Flash Sales
        </h1>
        <p className="mt-1 text-sm" style={{ color: 'var(--text-dim)' }}>
          Powered by Redis Lua atomic check-and-decrement. Zero oversell. One per student.
        </p>
      </header>

      {/* How it works */}
      <div
        className="grid grid-cols-1 gap-3 sm:grid-cols-3"
        style={{ borderTop: '1px solid var(--hair)', paddingTop: '1.5rem' }}
      >
        {[
          { n: '01', title: 'Stock locked in Redis', body: 'Available quantity is held in Redis — not MySQL. Decrements are atomic Lua scripts.' },
          { n: '02', title: 'Submit your claim', body: 'Your request is queued instantly. If stock is available your order is confirmed via async polling.' },
          { n: '03', title: 'Zero oversell guarantee', body: 'The Lua script is atomic: check + decrement runs as one operation. No race conditions.' },
        ].map((s, i) => (
          <div
            key={s.n}
            className="app-panel p-5 relative overflow-hidden animate-fade-up"
            style={{ borderLeft: '3px solid var(--signal)', animationDelay: `${i * 80}ms` }}
          >
            <div
              style={{
                position: 'absolute', top: 0, right: 0,
                width: 44, height: 44,
                background: 'var(--surface-inset)',
                display: 'flex', alignItems: 'center', justifyContent: 'center',
                borderBottomLeftRadius: 8,
              }}
            >
              <span className="app-mono text-xs font-bold" style={{ color: 'var(--signal)' }}>{s.n}</span>
            </div>
            <h3 className="font-display text-sm font-bold uppercase tracking-tight pr-12" style={{ color: 'var(--text)' }}>{s.title}</h3>
            <p className="mt-2 text-sm leading-relaxed" style={{ color: 'var(--text-dim)' }}>{s.body}</p>
          </div>
        ))}
      </div>

      {loading ? <Spinner /> : (
        <>
          {/* Live */}
          {live.length > 0 && (
            <section>
              <div className="app-section-head">
                <div className="flex items-center gap-2">
                  <span className="status-dot status-dot-green" />
                  <h2 className="font-display text-xl font-extrabold uppercase tracking-tight">Live now</h2>
                </div>
                <span className="app-badge badge-signal">{live.length} active</span>
              </div>
              <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
                {live.map((e) => <SeckillCard key={e.id} event={e} />)}
              </div>
            </section>
          )}

          {/* Upcoming */}
          {upcoming.length > 0 && (
            <section>
              <div className="app-section-head">
                <h2 className="font-display text-xl font-extrabold uppercase tracking-tight">Upcoming</h2>
                <span className="app-badge badge-muted">{upcoming.length} scheduled</span>
              </div>
              <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3">
                {upcoming.map((e) => <SeckillCard key={e.id} event={e} />)}
              </div>
            </section>
          )}

          {/* All / empty */}
          {events.length === 0 && (
            <div
              className="flex flex-col items-center justify-center py-20"
              style={{ border: '1px dashed var(--hair-strong)', borderRadius: 12 }}
            >
              <span className="app-mono text-[11px] uppercase tracking-[0.18em]" style={{ color: 'var(--text-faint)' }}>
                No flash sales scheduled right now
              </span>
            </div>
          )}

          {/* Ended */}
          {ended.length > 0 && (
            <section>
              <div className="app-section-head">
                <h2 className="font-display text-lg font-bold uppercase tracking-tight" style={{ color: 'var(--text-faint)' }}>
                  Past Sales
                </h2>
              </div>
              <div className="grid grid-cols-1 gap-5 sm:grid-cols-2 lg:grid-cols-3 opacity-50">
                {ended.map((e) => <SeckillCard key={e.id} event={e} />)}
              </div>
            </section>
          )}
        </>
      )}
    </div>
  );
}
