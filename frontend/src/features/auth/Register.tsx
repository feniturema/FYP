import { useState } from 'react';
import { useNavigate, Link } from 'react-router-dom';
import { authApi } from '../../services/api';
import { isUkmEmail } from '../../utils/validators';
import Button from '../../components/common/Button';
import Input from '../../components/common/Input';

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
    <AuthCard title="Create your account" subtitle="UKM students & staff only">
      <form onSubmit={submit} className="space-y-4">
        <Input label="Full name" value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })} required />
        <Input label="UKM email" type="email" placeholder="you@siswa.ukm.edu.my"
          value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} required />
        <Input label="Password" type="password" value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })} required minLength={6} />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <Button type="submit" full disabled={loading}>
          {loading ? 'Creating…' : 'Register'}
        </Button>
      </form>
      <p className="mt-4 text-center text-sm text-gray-600">
        Already have an account? <Link to="/login" className="text-ukm-700 hover:underline">Login</Link>
      </p>
    </AuthCard>
  );
}

export function AuthCard({ title, subtitle, children }: {
  title: string; subtitle?: string; children: React.ReactNode;
}) {
  return (
    <div className="mx-auto mt-10 max-w-md rounded-2xl border border-gray-200 bg-white p-8 shadow-sm">
      <h1 className="text-2xl font-bold text-ukm-700">{title}</h1>
      {subtitle && <p className="mb-6 mt-1 text-sm text-gray-500">{subtitle}</p>}
      {children}
    </div>
  );
}
