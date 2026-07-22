import { useEffect, useRef, ReactNode } from 'react';

export default function ScrollReveal({
  children,
  delay = 0,
  className = '',
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const obs = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          el.style.animationDelay = `${delay}s`;
          el.classList.add('sr-visible');
          obs.unobserve(el);
        }
      },
      { rootMargin: '-80px' }
    );
    obs.observe(el);
    return () => obs.disconnect();
  }, [delay]);

  return (
    <div ref={ref} className={`sr-hidden ${className}`}>
      {children}
    </div>
  );
}
