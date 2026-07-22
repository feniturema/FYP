import { useRef, useState } from 'react';
import { uploadApi } from '../../services/api';

interface Props {
  value?: string;
  onChange: (url: string) => void;
  label?: string;
}

export default function ImageUpload({ value, onChange, label = 'Image' }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');

  const handleFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(''); setUploading(true);
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
      <span className="app-label block">{label}</span>
      <div className="flex items-center gap-3">
        {value && (
          <img src={value} alt="preview"
            style={{ width: 64, height: 64, objectFit: 'cover', border: '1px solid var(--hair)', borderRadius: 12 }} />
        )}
        <div className="flex-1 space-y-1.5">
          <button
            type="button"
            onClick={() => inputRef.current?.click()}
            disabled={uploading}
            className="btn btn-outline py-2"
            style={{ borderStyle: 'dashed' }}
          >
            {uploading ? 'Uploading…' : value ? 'Replace image' : 'Upload image'}
          </button>
          <input ref={inputRef} type="file" accept="image/*" className="hidden" onChange={handleFile} />
          <p className="text-xs" style={{ color: 'var(--text-faint)' }}>JPEG, PNG, WebP · max 5 MB</p>
        </div>
      </div>
      {/* type="text" (not "url"): uploaded local images resolve to relative
          paths like "/uploads/xxx.jpg", which fail native type="url" validation
          and would block the whole form from submitting. */}
      <input
        type="text"
        inputMode="url"
        placeholder="or paste image URL"
        value={value ?? ''}
        onChange={(e) => onChange(e.target.value)}
        className="app-input"
      />
      {error && <p className="text-xs" style={{ color: 'var(--signal)' }}>{error}</p>}
    </div>
  );
}
