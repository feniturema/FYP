import { useEffect, useRef } from 'react';

/**
 * Signature effect: a large radial glow that follows the cursor across the whole
 * page (the "spotlight"). Fixed, pointer-events-none, GPU-friendly (updates a single
 * background-position via rAF). Disabled on coarse pointers / reduced-motion.
 */
export default function PageSpotlight() {
  const ref = useRef<HTMLDivElement>(null);
  const frame = useRef<number>(0);
  const target = useRef({ x: window.innerWidth / 2, y: window.innerHeight / 2 });

  useEffect(() => {
    const fine = window.matchMedia('(hover: hover) and (pointer: fine)').matches;
    const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (!fine || reduced) return;

    const onMove = (e: MouseEvent) => {
      target.current = { x: e.clientX, y: e.clientY };
      if (!frame.current) {
        frame.current = requestAnimationFrame(apply);
      }
    };
    const apply = () => {
      frame.current = 0;
      const el = ref.current;
      if (el) {
        el.style.background = `radial-gradient(600px circle at ${target.current.x}px ${target.current.y}px, rgba(124,58,237,0.16), rgba(34,211,238,0.06) 35%, transparent 60%)`;
      }
    };
    window.addEventListener('mousemove', onMove, { passive: true });
    return () => {
      window.removeEventListener('mousemove', onMove);
      if (frame.current) cancelAnimationFrame(frame.current);
    };
  }, []);

  return <div ref={ref} aria-hidden className="pointer-events-none fixed inset-0 z-30" />;
}
