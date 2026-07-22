/**
 * Animated aurora / gradient-mesh background for the hero. Pure CSS blobs (GPU blur),
 * plus a faint perspective grid overlay. Sits behind content (-z).
 */
export default function AuroraBackground() {
  return (
    <div aria-hidden className="pointer-events-none absolute inset-0 overflow-hidden">
      {/* deep base */}
      <div className="absolute inset-0 bg-void-950" />

      {/* aurora blobs */}
      <div className="absolute -left-32 -top-24 h-[34rem] w-[34rem] rounded-full bg-neon-purple/30 blur-[120px] animate-aurora" />
      <div className="absolute right-[-10rem] top-10 h-[30rem] w-[30rem] rounded-full bg-neon-cyan/25 blur-[130px] animate-aurora [animation-delay:-6s]" />
      <div className="absolute bottom-[-12rem] left-1/3 h-[32rem] w-[32rem] rounded-full bg-neon-indigo/25 blur-[140px] animate-aurora [animation-delay:-11s]" />

      {/* perspective grid */}
      <div className="grid-overlay absolute inset-0" />

      {/* vignette to seat content */}
      <div className="absolute inset-0 bg-gradient-to-b from-transparent via-transparent to-void-950" />
    </div>
  );
}
