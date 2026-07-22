import { Link, useLocation, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';

export default function Navbar() {
  const { user, isAuthenticated, isAdmin, logout } = useAuthStore();
  const cartCount = useCartStore((s) => s.lines.length);
  const navigate = useNavigate();
  const location = useLocation();

  const onLogout = () => { logout(); navigate('/login'); };
  const active = (path: string) =>
    location.pathname === path || location.pathname.startsWith(path + '/');

  return (
    <nav className="sticky top-0 z-30 bg-white" style={{ borderBottom: '1px solid var(--hair)' }}>
      <div className="mx-auto flex max-w-7xl items-center justify-between px-5 py-3.5">
        {/* Brand */}
        <Link to="/marketplace" className="flex items-center gap-2 group">
          <span className="app-mono text-[11px] font-bold uppercase tracking-[0.2em]" style={{ color: 'var(--signal)' }}>
            FTSM
          </span>
          <span style={{ width: 1, height: 14, background: 'var(--hair-strong)', display: 'inline-block' }} />
          <span className="app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-dim)' }}>
            Marketplace
          </span>
        </Link>

        {/* Nav links */}
        <div className="hidden items-center gap-1 md:flex">
          <Link to="/marketplace" className={`nav-link ${active('/marketplace') ? 'nav-link-active' : ''}`}>
            Shop
          </Link>
          <Link to="/seckill" className={`nav-link ${active('/seckill') ? 'nav-link-active' : ''}`}>
            Flash Sale
          </Link>
          {isAuthenticated() && (
            <Link to="/sell" className={`nav-link ${active('/sell') ? 'nav-link-active' : ''}`}>
              Sell
            </Link>
          )}
          {isAuthenticated() && (
            <Link to="/orders" className={`nav-link ${active('/orders') ? 'nav-link-active' : ''}`}>
              Orders
            </Link>
          )}
          {isAdmin() && (
            <Link to="/admin" className={`nav-link ${active('/admin') ? 'nav-link-active' : ''}`}>
              Admin
            </Link>
          )}
        </div>

        {/* Right side */}
        <div className="flex items-center gap-2">
          {isAuthenticated() && (
            <Link
              to="/cart"
              className="relative flex items-center gap-1.5 nav-link"
              aria-label="Cart"
            >
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
                <circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/>
                <path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/>
              </svg>
              <span className="hidden sm:inline">Cart</span>
              {cartCount > 0 && (
                <span
                  className="absolute -right-1.5 -top-1.5 flex h-4 w-4 items-center justify-center app-mono text-[9px] font-bold text-white"
                  style={{ background: 'var(--signal)', borderRadius: '50%' }}
                >
                  {cartCount}
                </span>
              )}
            </Link>
          )}

          {isAuthenticated() ? (
            <div className="flex items-center gap-3 ml-2">
              <Link
                to="/profile"
                className="hidden sm:flex items-center gap-2 group"
                style={{ textDecoration: 'none' }}
              >
                <span
                  className="flex h-7 w-7 items-center justify-center font-display text-[11px] font-bold text-white transition-opacity group-hover:opacity-80"
                  style={{ background: 'var(--brand)', borderRadius: 12 }}
                >
                  {user?.name?.split(' ').map((w: string) => w[0]).join('').toUpperCase().slice(0, 2)}
                </span>
                <span
                  className="app-mono text-[11px] uppercase tracking-wider"
                  style={{ color: 'var(--text-faint)' }}
                >
                  {user?.name?.split(' ')[0]}
                </span>
              </Link>
              <button
                onClick={onLogout}
                className="btn btn-outline py-1.5 text-[10px]"
              >
                Logout
              </button>
            </div>
          ) : (
            <Link to="/login" className="btn btn-brand py-1.5">
              Login
            </Link>
          )}
        </div>
      </div>
    </nav>
  );
}
