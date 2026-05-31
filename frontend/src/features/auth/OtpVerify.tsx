import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { authApi } from '../../services/api';
import { useAuthStore } from '../../store/useAuthStore';
import Button from '../../components/common/Button';
import Input from '../../components/common/Input';
import { AuthCard } from './Register';

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
    setError('');
    setLoading(true);
    try {
      const auth = await authApi.verifyOtp({ email, code });
      setSession(auth);
      navigate('/');
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Verification failed.');
    } finally {
      setLoading(false);
    }
  };

  const resend = async () => {
    setError('');
    setNotice('');
    setResending(true);
    try {
      const res = await authApi.resendOtp({ email });
      setNotice(res.message);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Failed to resend code.');
    } finally {
      setResending(false);
    }
  };

  return (
    <AuthCard title="Verify your email" subtitle="Enter the 6-digit code sent to your UKM email">
      <form onSubmit={submit} className="space-y-4">
        <Input label="Email" type="email" value={email}
          onChange={(e) => setEmail(e.target.value)} required />
        <Input label="Verification code" value={code} maxLength={6} placeholder="123456"
          onChange={(e) => setCode(e.target.value)} required />
        {error && <p className="text-sm text-red-600">{error}</p>}
        {notice && <p className="text-sm text-green-700">{notice}</p>}
        <Button type="submit" full disabled={loading}>
          {loading ? 'Verifying…' : 'Verify & continue'}
        </Button>
        <Button type="button" variant="outline" full disabled={resending || !email} onClick={resend}>
          {resending ? 'Sending…' : 'Resend code'}
        </Button>
      </form>
      <p className="mt-4 text-center text-xs text-gray-400">
        Tip: in local dev (mail disabled) the code is printed in the backend console.
      </p>
    </AuthCard>
  );
}
