export function rm(amount?: number): string {
  if (amount == null) return '—';
  return `RM ${Number(amount).toFixed(2)}`;
}

export function formatDateTime(iso?: string): string {
  if (!iso) return '';
  return new Date(iso).toLocaleString();
}
