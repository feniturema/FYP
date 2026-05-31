import { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { authApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import Button from '../../components/common/Button';
import Input from '../../components/common/Input';
import { AuthCard } from './Register';

export default function Login() {
  const [form, setForm] = useState({ email: '', password: '' });
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const setSession = useAuthStore((s) => s.setSession);
  const navigate = useNavigate();

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const auth = await authApi.login(form);
      setSession(auth);
      navigate('/');
    } catch (err: any) {
      const msg = err.response?.data?.message ?? 'Login failed.';
      setError(msg);
    } finally {
      setLoading(false);
    }
  };

  return (
    <AuthCard title="Welcome back" subtitle="Sign in to FTSM Marketplace">
      <form onSubmit={submit} className="space-y-4">
        <Input label="UKM email" type="email" value={form.email}
          onChange={(e) => setForm({ ...form, email: e.target.value })} required />
        <Input label="Password" type="password" value={form.password}
          onChange={(e) => setForm({ ...form, password: e.target.value })} required />
        {error && <p className="text-sm text-red-600">{error}</p>}
        <Button type="submit" full disabled={loading}>
          {loading ? 'Signing in…' : 'Login'}
        </Button>
      </form>
      <p className="mt-4 text-center text-sm text-gray-600">
        New here? <Link to="/register" className="text-ukm-700 hover:underline">Create an account</Link>
      </p>
    </AuthCard>
  );
}
