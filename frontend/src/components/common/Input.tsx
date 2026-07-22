import type { InputHTMLAttributes } from 'react';

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
}

export default function Input({ label, className = '', ...rest }: Props) {
  return (
    <label className="block">
      {label && <span className="app-label mb-1.5 block">{label}</span>}
      <input className={`app-input ${className}`} {...rest} />
    </label>
  );
}
