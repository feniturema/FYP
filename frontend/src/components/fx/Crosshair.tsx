import { useEffect, useRef, useState } from 'react';

/**
 * Engineering-style cursor tracking: a thin full-bleed crosshair (reticle) that
 * follows the pointer, with a live monospace coordinate readout. Replaces the
 * generic glow "spotlight". rAF-throttled; fine-pointer + motion-safe only.
 */
export default function Crosshair() {
  const vx = useRef<HTMLDivElement>(null);
  const hy = useRef<HTMLDivElement>(null);
  const tag = useRef<HTMLDivElement>(null);
  const frame = useRef(0);
  const pos = useRef({ x: -100, y: -100 });
  const [on, setOn] = useState(false);

  useEffect(() => {
    const fine = window.matchMedia('(hover: hover) and (pointer: fine)').matches;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!fine || reduced) return;

    const onMove = (e: MouseEvent) => {
      pos.current = { x: e.clientX, y: e.clientY };
      if (!on) setOn(true);
      if (!frame.current) frame.current = requestAnimationFrame(apply);
    };
    const apply = () => {
      frame.current = 0;
      const { x, y } = pos.current;
      if (vx.current) vx.current.style.transform = `translateX(${x}px)`;
      if (hy.current) hy.current.style.transform = `translateY(${y}px)`;
      if (tag.current) {
        tag.current.style.transform = `translate(${x + 14}px, ${y + 14}px)`;
        tag.current.textContent = `x:${String(x).padStart(4, '0')} y:${String(y).padStart(4, '0')}`;
      }
    };
    window.addEventListener('mousemove', onMove, { passive: true });
    return () => {
      window.removeEventListener('mousemove', onMove);
      if (frame.current) cancelAnimationFrame(frame.current);
    };
  }, [on]);

  return (
    <div aria-hidden className="pointer-events-none" style={{ opacity: on ? 1 : 0, transition: 'opacity 200ms' }}>
      <div ref={vx} className="reticle-line left-0 top-0 h-full w-px" />
      <div ref={hy} className="reticle-line left-0 top-0 h-px w-full" />
      <div
        ref={tag}
        className="mono fixed left-0 top-0 z-[60] text-[10px] tracking-wider text-signal"
      >
        x:0000 y:0000
      </div>
    </div>
  );
}
