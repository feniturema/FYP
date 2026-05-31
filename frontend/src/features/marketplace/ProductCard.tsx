import { rm } from '../../utils/format';
import { Link } from 'react-router-dom';

interface Props {
  title: string;
  price: number;
  imageUrl?: string;
  badge?: string;
  subtitle?: string;
  onBuy?: () => void;
  buyLabel?: string;
  disabled?: boolean;
  href?: string;
  onAddToCart?: () => void;
}

export default function ProductCard({
  title, price, imageUrl, badge, subtitle, onBuy, buyLabel = 'Buy', disabled, href, onAddToCart,
}: Props) {
  const body = (
    <>
      <div className="relative aspect-square bg-gray-100">
        {imageUrl ? (
          <img src={imageUrl} alt={title} className="h-full w-full object-cover" />
        ) : (
          <div className="flex h-full items-center justify-center text-gray-300">No image</div>
        )}
        {badge && (
          <span className="absolute left-2 top-2 rounded-full bg-ukm-700 px-2 py-0.5 text-xs font-semibold text-white">
            {badge}
          </span>
        )}
      </div>
      <div className="p-3">
        <h3 className="truncate text-sm font-semibold text-gray-800">{title}</h3>
        {subtitle && <p className="truncate text-xs text-gray-500">{subtitle}</p>}
        <div className="mt-2 flex items-center justify-between">
          <span className="font-bold text-ukm-700">{rm(price)}</span>
          <div className="flex items-center gap-1">
            {onBuy && (
              <button
                onClick={(e) => { e.preventDefault(); onBuy(); }}
                disabled={disabled}
                className="rounded-lg bg-ukm-700 px-3 py-1 text-xs font-semibold text-white hover:bg-ukm-800 disabled:opacity-50"
              >
                {buyLabel}
              </button>
            )}
            {onAddToCart && !disabled && (
              <button
                onClick={(e) => { e.preventDefault(); onAddToCart(); }}
                title="Add to cart"
                className="rounded-lg border border-ukm-300 px-2 py-1 text-xs text-ukm-700 hover:bg-ukm-50"
              >
                🛒
              </button>
            )}
          </div>
        </div>
      </div>
    </>
  );

  return (
    <div className="overflow-hidden rounded-xl border border-gray-200 bg-white shadow-sm transition hover:shadow-md">
      {href ? <Link to={href} className="block">{body}</Link> : body}
    </div>
  );
}
