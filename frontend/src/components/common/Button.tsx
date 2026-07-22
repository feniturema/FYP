import type { ButtonHTMLAttributes } from 'react';

interface Props extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'outline' | 'ghost';
  full?: boolean;
}

export default function Button({ variant = 'primary', full, className = '', ...rest }: Props) {
  const variants: Record<string, string> = {
    primary: 'btn-brand',
    outline: 'btn-outline',
    ghost: 'btn-ghost',
  };
  return (
    <button
      className={`btn ${variants[variant]} ${full ? 'w-full justify-center' : ''} ${className}`}
      {...rest}
    />
  );
}
