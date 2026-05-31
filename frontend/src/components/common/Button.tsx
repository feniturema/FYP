import type { ButtonHTMLAttributes } from 'react';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'outline' | 'ghost';
  full?: boolean;
}

export default function Button({ variant = 'primary', full, className = '', ...rest }: Props) {
  const base =
    'inline-flex items-center justify-center rounded-lg px-4 py-2 text-sm font-semibold transition disabled:opacity-50 disabled:cursor-not-allowed';
  const variants: Record<string, string> = {
    primary: 'bg-ukm-700 text-white hover:bg-ukm-800',
    outline: 'border border-ukm-700 text-ukm-700 hover:bg-ukm-50',
    ghost: 'text-ukm-700 hover:bg-ukm-50',
  };
  return (
    <button
      className={`${base} ${variants[variant]} ${full ? 'w-full' : ''} ${className}`}
      {...rest}
    />
  );
}
