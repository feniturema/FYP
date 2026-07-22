import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { authApi } from '../../services/api';
import { isUkmEmail } from '../../utils/validators';
import { AuthLayout } from './Login';

export default function Register() {
  const [form, setForm] = useState({ name: '', email: '', password: '' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    if (!isUkmEmail(form.email)) {
      setError('Use your @ukm.edu.my or @siswa.ukm.edu.my email.');
      return;
    }
    setLoading(true);
    try {
      await authApi.register(form);
      navigate('/verify-otp', { state: { email: form.email } });
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Registration failed.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthLayout
      title="Create account"
      subtitle="UKM students & staff only"
      foot={<>Already have an account? <Link to="/login" className="font-semibold" style={{ color: 'var(--signal)' }}>Login</Link></>}
    >
      <form onSubmit={submit} className="space-y-4">
        <FieldGroup label="Full Name">
          <input
            className="app-input"
            value={form.name}
            placeholder="Ahmad bin Abdullah"
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            required
          />
        </FieldGroup>
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
            placeholder="Minimum 6 characters"
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
            required
            minLength={6}
          />
        </FieldGroup>
        {error && <p className="text-sm" style={{ color: 'var(--danger)' }}>{error}</p>}
        <button type="submit" disabled={loading} className="btn btn-brand w-full justify-center py-3">
          {loading ? 'Creating account…' : 'Register & verify email →'}
        </button>
      </form>
    </AuthLayout>
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
