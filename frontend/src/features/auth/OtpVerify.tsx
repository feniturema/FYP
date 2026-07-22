import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import { AuthLayout } from './Login';

export default function OtpVerify() {
  const location = useLocation();
  const navigate = useNavigate();
  const setSession = useAuthStore((s) => s.setSession);
  const [email, setEmail] = useState((location.state as any)?.email ?? '');
  const [code, setCode] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(''); setLoading(true);
    try {
      const auth = await authApi.verifyOtp({ email, code });
      setSession(auth);
      navigate('/marketplace');
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Verification failed.');
    } finally {
      setLoading(false);
    }
  };

  const resend = async () => {
    setError(''); setNotice(''); setResending(true);
    try {
      const res = await authApi.resendOtp({ email });
      setNotice(res.message);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to resend.');
    } finally {
      setResending(false);
    }
  };

  return (
    <AuthLayout title="Verify your email" subtitle="Enter the 6-digit code sent to your UKM email">
      <form onSubmit={submit} className="space-y-4">
        <label className="block">
          <span className="app-label mb-1.5 block">Email</span>
          <input className="app-input" type="email" value={email}
            onChange={(e) => setEmail(e.target.value)} required />
        </label>
        <label className="block">
          <span className="app-label mb-1.5 block">Verification Code</span>
          <input
            className="app-input text-center app-mono text-xl tracking-[0.4em]"
            placeholder="000000"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            maxLength={6}
            required
          />
        </label>
        {error && <p className="text-sm" style={{ color: 'var(--danger)' }}>{error}</p>}
        {notice && <p className="text-sm" style={{ color: '#10b981' }}>{notice}</p>}
        <button type="submit" disabled={loading} className="btn btn-brand w-full justify-center py-3">
          {loading ? 'Verifying…' : 'Verify & continue →'}
        </button>
        <button
          type="button"
          disabled={resending || !email}
          onClick={resend}
          className="btn btn-outline w-full justify-center py-3"
        >
          {resending ? 'Sending…' : 'Resend code'}
        </button>
      </form>
      <p className="mt-4 text-center text-xs" style={{ color: 'var(--text-faint)' }}>
        In local dev, the OTP is printed in the backend console.
      </p>
    </AuthLayout>
  );
}
