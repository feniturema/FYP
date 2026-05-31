import { useRef, useState } from 'react';
import { uploadApi } from '../../services/api';

interface Props {
  value?: string;
  onChange: (url: string) => void;
  label?: string;
}

/**
 * File-picker that uploads to POST /api/upload and returns the served URL.
 * Falls back to manual URL input if no file is chosen.
 */
export default function ImageUpload({ value, onChange, label = 'Image' }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError('');
    setUploading(true);
    try {
      const { url } = await uploadApi.image(file);
      onChange(url);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Upload failed.');
    } finally {
      setUploading(false);
      if (inputRef.current) inputRef.current.value = '';
    }
  };

  return (
    <div className="space-y-2">
      <span className="block text-sm font-medium text-gray-700">{label}</span>
      <div className="flex items-center gap-3">
        {value && (
          <img src={value} alt="preview" className="h-16 w-16 rounded-lg object-cover border border-gray-200" />
        )}
        <div className="flex-1 space-y-1">
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            disabled={uploading}
            className="rounded-lg border border-dashed border-ukm-400 px-4 py-2 text-sm text-ukm-700 hover:bg-ukm-50 disabled:opacity-50"
          >
            {uploading ? 'Uploading…' : value ? 'Replace image' : 'Upload image'}
          </button>
          <input ref={inputRef} type="file" accept="image/*" className="hidden" onChange={handleFile} />
          <p className="text-xs text-gray-400">JPEG, PNG, WebP, GIF · max 5 MB</p>
        </div>
      </div>
      {/* fallback: also allow pasting a URL */}
      <input
        type="url"
        placeholder="or paste image URL"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm focus:border-ukm-500 focus:outline-none"
      />
      {error && <p className="text-xs text-red-600">{error}</p>}
    </div>
  );
}
