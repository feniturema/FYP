// Mirrors the backend UKM domain rule so we fail fast on the client.
const UKM_EMAIL = /^[a-zA-Z0-9._%+-]+@(siswa\.)?ukm\.edu\.my$/;

export function isUkmEmail(email: string): boolean {
  return UKM_EMAIL.test(email.trim().toLowerCase());
}
