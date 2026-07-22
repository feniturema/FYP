import { Link } from 'react-router-dom';
import { rm } from '../../utils/format';

interface Props {
  title: string;
  price: number;
  imageUrl?: string;
  badge?: string;
  badgeVariant?: 'brand' | 'signal' | 'muted';
  subtitle?: string;
  onBuy?: () => void;
  buyLabel?: string;
  disabled?: boolean;
  href?: string;
  onAddToCart?: () => void;
}

export default function ProductCard({
  title, price, imageUrl, badge, badgeVariant = 'brand', subtitle,
  onBuy, buyLabel = 'Buy', disabled, href, onAddToCart,
}: Props) {
  const body = (
    <>
      {/* Image with hover overlay */}
      <div className="product-img-wrap aspect-square" style={{ background: 'var(--surface-inset)' }}>
        {imageUrl ? (
          <img src={imageUrl} alt={title} className="product-card-image h-full w-full object-cover" />
        ) : (
          <div className="flex h-full items-center justify-center">
            <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1"
              style={{ color: 'var(--text-faint)' }}>
              <rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="8.5" cy="8.5" r="1.5"/>
              <polyline points="21 15 16 10 5 21"/>
            </svg>
          </div>
        )}
        {/* Hover overlay */}
        <div className="product-img-overlay">
          <span className="app-mono text-[10px] uppercase tracking-[0.14em] text-white font-medium">
            View →
          </span>
        </div>
        {badge && (
          <span className={`app-badge absolute left-2 top-2 badge-${badgeVariant}`}>
            {badge}
          </span>
        )}
      </div>

      {/* Info */}
      <div className="p-3 flex flex-col gap-2">
        {/* Title + subtitle */}
        <div>
          <h3 className="truncate text-sm font-semibold leading-tight" style={{ color: 'var(--text)' }}>{title}</h3>
          {subtitle && (
            <p className="mt-0.5 truncate text-[11px]" style={{ color: 'var(--text-faint)' }}>{subtitle}</p>
          )}
        </div>

        {/* Price + actions row */}
        <div className="flex items-center justify-between pt-1" style={{ borderTop: '1px solid var(--hair)' }}>
          <span className="font-display text-base font-bold" style={{ color: 'var(--brand)' }}>
            {rm(price)}
          </span>
          <div className="flex items-center gap-1.5">
            {onBuy && (
              <button
                onClick={(e) => { e.preventDefault(); onBuy(); }}
                disabled={disabled}
                className="btn btn-brand py-1 px-3 text-[10px]"
              >
                {buyLabel}
              </button>
            )}
            {onAddToCart && !disabled && (
              <button
                onClick={(e) => { e.preventDefault(); onAddToCart(); }}
                title="Add to cart"
                className="btn btn-outline py-1 px-2"
                style={{ minWidth: 28 }}
              >
                <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2">
                  <circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/>
                  <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/>
                </svg>
              </button>
            )}
          </div>
        </div>
      </div>
    </>
  );

  return (
    <div className="app-card">
      {href ? <Link to={href} className="block">{body}</Link> : body}
    </div>
  );
}
