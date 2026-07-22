import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';

export default function Login() {
  const [form, setForm] = useState({ email: '', password: '' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const setSession = useAuthStore((s) => s.setSession);
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(''); setLoading(true);
    try {
      const auth = await authApi.login(form);
      setSession(auth);
      navigate('/marketplace');
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Login failed.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      title="Welcome back"
      subtitle="Sign in to FTSM Marketplace"
      foot={<>New here? <Link to="/register" className="font-semibold" style={{ color: 'var(--signal)' }}>Create an account</Link></>}
    >
      <form onSubmit={submit} className="space-y-4">
        <FieldGroup label="UKM Email">
          <input
            className="app-input"
            type="email"
            placeholder="you@siswa.ukm.edu.my"
            value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
            required
          />
        </FieldGroup>
        <FieldGroup label="Password">
          <input
            className="app-input"
            type="password"
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
            required
          />
        </FieldGroup>
        {error && <p className="text-sm" style={{ color: 'var(--danger)' }}>{error}</p>}
        <button type="submit" disabled={loading} className="btn btn-brand w-full justify-center py-3">
          {loading ? 'Signing in…' : 'Login →'}
        </button>
      </form>
    </AuthLayout>
  );
}

export function AuthLayout({ title, subtitle, children, foot }: {
  title: string; subtitle?: string; children: React.ReactNode; foot?: React.ReactNode;
}) {
  return (
    <div className="min-h-screen" style={{ background: '#faf9f7' }}>
      {/* Top bar */}
      <header style={{ borderBottom: '1px solid var(--hair)', background: 'white' }}>
        <div className="mx-auto flex max-w-7xl items-center justify-between px-5 py-3.5">
          <Link to="/" className="flex items-center gap-2">
            <span className="app-mono text-[11px] font-bold uppercase tracking-[0.2em]" style={{ color: 'var(--signal)' }}>
              FTSM
            </span>
            <span style={{ width: 1, height: 14, background: 'var(--hair-strong)', display: 'inline-block' }} />
            <span className="app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-dim)' }}>
              Marketplace
            </span>
          </Link>
          <Link to="/marketplace" className="app-mono text-[11px] uppercase tracking-[0.14em]" style={{ color: 'var(--text-faint)' }}>
            Browse without account →
          </Link>
        </div>
      </header>

      <div className="mx-auto max-w-md px-4 py-16">
        {/* Card */}
        <div className="app-panel p-8 animate-fade-up">
          <div className="mb-6">
            <h1 className="font-display text-2xl font-extrabold uppercase tracking-tight">{title}</h1>
            {subtitle && <p className="mt-1 text-sm" style={{ color: 'var(--text-faint)' }}>{subtitle}</p>}
          </div>
          {children}
          {foot && (
            <p className="mt-5 text-center text-sm" style={{ color: 'var(--text-dim)' }}>{foot}</p>
          )}
        </div>

        {/* UKM notice */}
        <div
          className="mt-4 px-4 py-3 flex items-start gap-2.5 animate-fade-up"
          style={{
            border: '1px solid var(--hair)',
            background: 'var(--surface-raised)',
            borderRadius: 12,
            animationDelay: '80ms',
          }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
            style={{ color: 'var(--text-faint)', flexShrink: 0, marginTop: 1 }}>
            <circle cx="12" cy="12" r="10"/><path d="M12 16v-4M12 8h.01"/>
          </svg>
          <p className="text-xs" style={{ color: 'var(--text-faint)' }}>
            Only <strong>@ukm.edu.my</strong> and <strong>@siswa.ukm.edu.my</strong> email addresses are allowed.
            Email OTP verification required.
          </p>
        </div>
      </div>
    </div>
  );
}

function FieldGroup({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="app-label mb-1.5 block">{label}</span>
      {children}
    </label>
  );
}
