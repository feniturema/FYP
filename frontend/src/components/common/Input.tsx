import type { InputHTMLAttributes } from 'react';

interface Props extends InputHTMLAttributes<HTMLInputElement> {
  label?: string;
}

export default function Input({ label, className = '', ...rest }: Props) {
  return (
    <label className="block">
      {label && <span className="mb-1 block text-sm font-medium text-gray-700">{label}</span>}
      <input
        className={`w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-ukm-500 focus:outline-none focus:ring-1 focus:ring-ukm-500 ${className}`}
        {...rest}
      />
    </label>
  );
}
