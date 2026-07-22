export default function Spinner() {
  return (
    <div className="flex flex-col items-center justify-center py-16 gap-3">
      <div
        className="h-7 w-7 animate-spin"
        style={{
          border: '2px solid var(--hair-strong)',
          borderTopColor: 'var(--signal)',
          borderRadius: '50%',
          animationDuration: '600ms',
        }}
      />
      <span className="app-mono text-[10px] uppercase tracking-[0.2em]" style={{ color: 'var(--text-faint)' }}>
        Loading
      </span>
    </div>
  );
}
