import { Link, useNavigate } from 'react-router-dom';
import { useAuthStore } from '../../store/useAuthStore';
import { useCartStore } from '../../store/useCartStore';

export default function Navbar() {
  const { user, isAuthenticated, isAdmin, logout } = useAuthStore();
  const cartCount = useCartStore((s) => s.lines.length);
  const navigate = useNavigate();

  const onLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <nav className="border-b border-gray-200 bg-white">
      <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
        <Link to="/" className="text-lg font-extrabold text-ukm-700">
          FTSM<span className="text-gray-800">Marketplace</span>
        </Link>
        <div className="flex items-center gap-4 text-sm font-medium text-gray-700">
          <Link to="/" className="hover:text-ukm-700">Marketplace</Link>
          <Link to="/seckill" className="hover:text-ukm-700">SecKill</Link>
          {isAuthenticated() && <Link to="/sell" className="hover:text-ukm-700">Sell</Link>}
          {isAuthenticated() && <Link to="/orders" className="hover:text-ukm-700">Orders</Link>}
          {isAdmin() && <Link to="/admin" className="hover:text-ukm-700">Admin</Link>}
          {isAuthenticated() && (
            <Link to="/cart" className="relative hover:text-ukm-700" aria-label="Cart">
              🛒
              {cartCount > 0 && (
                <span className="absolute -right-2 -top-2 flex h-4 w-4 items-center justify-center rounded-full bg-ukm-700 text-[10px] font-bold text-white">
                  {cartCount}
                </span>
              )}
            </Link>
          )}
          {isAuthenticated() ? (
            <>
              <span className="text-gray-400">|</span>
              <span className="text-gray-600">{user?.name}</span>
              <button onClick={onLogout} className="text-ukm-700 hover:underline">Logout</button>
            </>
          ) : (
            <Link to="/login" className="rounded-lg bg-ukm-700 px-3 py-1.5 text-white hover:bg-ukm-800">
              Login
            </Link>
          )}
        </div>
      </div>
    </nav>
  );
}
