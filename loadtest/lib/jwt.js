// HS256 JWT signing for k6, matching the backend's JJWT tokens (sub = user id, role claim).
// The backend trusts any token signed with app.jwt.secret, so load tests mint tokens
// for synthetic user ids instead of registering real accounts.
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const HEADER = encoding.b64encode(JSON.stringify({ alg: 'HS256', typ: 'JWT' }), 'rawurl');

export function signHS256(claims, secret) {
  const body = encoding.b64encode(JSON.stringify(claims), 'rawurl');
  const signingInput = `${HEADER}.${body}`;
  const sig = crypto.hmac('sha256', secret, signingInput, 'binary');
  return `${signingInput}.${encoding.b64encode(sig, 'rawurl')}`;
}

export function studentToken(userId, secret, ttlSeconds = 3600) {
  const now = Math.floor(Date.now() / 1000);
  return signHS256({
    sub: String(userId),
    email: `lt${userId}@siswa.ukm.edu.my`,
    role: 'STUDENT',
    name: `loadtest ${userId}`,
    iat: now,
    exp: now + ttlSeconds,
  }, secret);
}
