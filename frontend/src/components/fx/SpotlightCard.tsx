import { useRef, type ReactNode } from 'react';

/**
 * Glass card whose border + inner glow follow the cursor (Linear/Vercel style).
 * Updates --mx/--my CSS vars on pointer move; the visual lives in `.spotlight-card`
 * (index.css). Cheap: only sets two CSS custom properties.
 */
export default function SpotlightCard({
  children,
  className = '',
}: {
  children: ReactNode;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);

  const onMove = (e: React.MouseEvent<HTMLDivElement>) => {
    const el = ref.current;
    if (!el) return;
    const r = el.getBoundingClientRect();
    el.style.setProperty('--mx', `${e.clientX - r.left}px`);
    el.style.setProperty('--my', `${e.clientY - r.top}px`);
  };

  return (
    <div ref={ref} onMouseMove={onMove} className={`spotlight-card ${className}`}>
      {children}
    </div>
  );
}
