import { Link } from 'react-router-dom';
import Crosshair from '../../components/fx/Crosshair';
import ScrollReveal from '../../components/fx/ScrollReveal';

const RUNTIME = [
  ['CONCURRENCY', '10K+'],
  ['OVERSELL', '0'],
  ['AI.TOOLS', '04'],
  ['SEARCH.P95', '~2.0s'],
  ['AUTH', 'UKM-OTP'],
];

const CAPS = [
  {
    id: '01',
    tag: 'REDIS + LUA',
    title: 'Atomic SecKill engine',
    body: 'Check-and-decrement runs as a single Lua script in Redis. Thousands of concurrent flash-sale buys, zero oversell, MySQL untouched on the hot path.',
  },
  {
    id: '02',
    tag: 'FUNCTION-CALLING',
    title: 'Agentic assistant',
    body: 'Not a chatbot — an agent. It calls real backend tools to search products, read live SecKill stock and pull your orders, then returns one-click actions.',
  },
  {
    id: '03',
    tag: 'LLM RERANK',
    title: 'Semantic search',
    body: 'Intent over keywords. “Something to keep me warm on campus” resolves to the Hoodie. Candidate prefilter, LLM rerank, keyword fallback if the model is down.',
  },
  {
    id: '04',
    tag: 'OTP + JWT',
    title: 'UKM-only access',
    body: 'Email-OTP verification locked to @ukm.edu.my / @siswa.ukm.edu.my, then stateless JWT. A trusted, students-only marketplace.',
  },
];

const TICKER = [
  'REDIS', 'LUA', 'ATOMIC SECKILL', 'ZERO OVERSELL', 'DEEPSEEK AGENT',
  'SEMANTIC SEARCH', 'REDIS STREAMS', 'UKM-OTP', 'JWT', 'MOCK FPX',
];

export default function Landing() {
  return (
    <div className="theme-dark relative min-h-screen overflow-x-hidden font-body text-bone">
      <Crosshair />

      <div className="relative z-10">
        {/* ===== Top bar ===== */}
        <header className="border-b hairline">
          <div className="mx-auto flex max-w-7xl items-center justify-between px-6 py-4 text-[11px] uppercase mono tracking-[0.18em]">
            <Link to="/" className="font-medium text-bone">
              FTSM<span className="text-bone-faint">//</span>MARKETPLACE
            </Link>
            <span className="hidden items-center gap-2 text-bone-dim md:flex">
              <span className="h-1.5 w-1.5 bg-signal" /> SYSTEM ONLINE
            </span>
            <Link to="/marketplace" className="text-bone-dim transition-colors hover:text-signal">
              [ ENTER&nbsp;&rarr; ]
            </Link>
          </div>
        </header>

        {/* ===== Hero ===== */}
        <section className="relative overflow-hidden border-b hairline">
          <div className="grid-faint pointer-events-none absolute inset-0 opacity-60" />
          {/* oversized ghost wordmark — grid-breaking */}
          <div
            aria-hidden
            className="pointer-events-none absolute -right-10 bottom-[-4rem] select-none font-display text-[34vw] font-extrabold leading-none text-bone/[0.025] md:text-[22vw]"
          >
            FTSM
          </div>

          <div className="relative mx-auto grid max-w-7xl grid-cols-1 gap-12 px-6 py-20 md:grid-cols-12 md:py-28">
            {/* left: headline */}
            <div className="md:col-span-8">
              <div className="kicker mb-7 animate-fade-up">
                <span className="accent">01</span> / CAMPUS COMMERCE ENGINE
              </div>

              <h1
                className="font-display text-[2rem] font-extrabold uppercase leading-[0.92] tracking-tight sm:text-[3.1rem] md:text-8xl animate-fade-up"
                style={{ animationDelay: '0.08s' }}
              >
                A marketplace<br />
                built like<br />
                <span className="accent">infrastructure.</span>
              </h1>

              <p
                className="mt-8 max-w-xl text-[15px] leading-relaxed text-bone-dim animate-fade-up"
                style={{ animationDelay: '0.2s' }}
              >
                Buy, sell and snipe flash deals across the UKM community — on a
                high-concurrency SecKill core and an AI agent that calls real tools,
                not canned replies.
              </p>

              <div
                className="mt-10 flex flex-wrap items-center gap-3 animate-fade-up"
                style={{ animationDelay: '0.3s' }}
              >
                <Link to="/marketplace" className="btn-spec">Enter marketplace &rarr;</Link>
                <Link to="/seckill" className="btn-line">Flash sales</Link>
              </div>
            </div>

            {/* right: runtime spec panel */}
            <div
              className="md:col-span-4 md:pt-2 animate-fade-up"
              style={{ animationDelay: '0.4s' }}
            >
              <div className="spec-panel">
                <div className="flex items-center justify-between border-b hairline px-4 py-3">
                  <span className="mono text-[10px] uppercase tracking-[0.18em] text-bone-dim">runtime / status</span>
                  <span className="mono text-[10px] text-signal">LIVE<span className="ml-1 animate-blink">_</span></span>
                </div>
                <dl>
                  {RUNTIME.map(([k, v], i) => (
                    <div
                      key={k}
                      className={`flex items-baseline justify-between px-4 py-3.5 ${i ? 'border-t hairline' : ''}`}
                    >
                      <dt className="mono text-[11px] uppercase tracking-[0.14em] text-bone-faint">{k}</dt>
                      <dd className="font-display text-xl font-bold">{v}</dd>
                    </div>
                  ))}
                </dl>
              </div>
            </div>
          </div>
        </section>

        {/* ===== Ticker ===== */}
        <section className="overflow-hidden border-b hairline py-3">
          <div className="flex w-max animate-marquee whitespace-nowrap">
            {[0, 1].map((dup) => (
              <div key={dup} className="flex items-center" aria-hidden={dup === 1}>
                {TICKER.map((t) => (
                  <span key={t} className="mono flex items-center text-[11px] uppercase tracking-[0.2em] text-bone-dim">
                    <span className="px-6">{t}</span>
                    <span className="accent">/</span>
                  </span>
                ))}
              </div>
            ))}
          </div>
        </section>

        {/* ===== Capabilities (spec list) ===== */}
        <section className="mx-auto max-w-7xl px-6 py-20 md:py-28">
          <ScrollReveal className="mb-12 flex flex-wrap items-end justify-between gap-4">
            <h2 className="font-display text-4xl font-extrabold uppercase tracking-tight md:text-6xl">
              Capabilities
            </h2>
            <span className="kicker"><span className="accent">02</span> / what it actually does</span>
          </ScrollReveal>

          <div className="border-b hairline">
            {CAPS.map((c, i) => (
              <ScrollReveal key={c.id} delay={i * 0.05}>
                <div className="spec-row grid grid-cols-1 gap-4 px-2 py-7 md:grid-cols-12 md:gap-8 md:py-9">
                  <div className="mono text-sm text-signal md:col-span-1">{c.id}</div>
                  <h3 className="font-display text-2xl font-bold uppercase leading-tight md:col-span-4 md:text-3xl">
                    {c.title}
                  </h3>
                  <p className="max-w-xl text-sm leading-relaxed text-bone-dim md:col-span-5">{c.body}</p>
                  <div className="mono self-start text-[10px] uppercase tracking-[0.16em] text-bone-faint md:col-span-2 md:text-right">
                    {c.tag}
                  </div>
                </div>
              </ScrollReveal>
            ))}
          </div>
        </section>

        {/* ===== Agent (terminal log) ===== */}
        <section className="border-y hairline bg-ink-900/40">
          <div className="mx-auto grid max-w-7xl grid-cols-1 gap-12 px-6 py-20 md:grid-cols-12 md:py-28">
            <ScrollReveal className="md:col-span-5">
              <span className="kicker mb-6"><span className="accent">03</span> / agentic assistant</span>
              <h2 className="font-display text-4xl font-extrabold uppercase leading-[0.95] tracking-tight md:text-5xl">
                Ask in plain<br />words. It calls<br />the <span className="accent">tools.</span>
              </h2>
              <p className="mt-6 max-w-md text-sm leading-relaxed text-bone-dim">
                The assistant runs an OpenAI-style function-calling loop against the live backend —
                search, stock, orders — then replies and hands you add-to-cart actions.
              </p>
              <Link to="/marketplace" className="btn-line mt-8">Run a query &rarr;</Link>
            </ScrollReveal>

            <ScrollReveal delay={0.12} className="md:col-span-7">
              <div className="spec-panel">
                <div className="flex items-center gap-2 border-b hairline px-4 py-3">
                  <span className="h-2.5 w-2.5 rounded-full border border-bone-faint" />
                  <span className="mono text-[10px] uppercase tracking-[0.18em] text-bone-dim">assistant.log</span>
                </div>
                <pre className="mono overflow-x-auto px-4 py-5 text-[12.5px] leading-relaxed text-bone-dim">
<span className="text-bone">&gt; </span>find me something cheap to keep warm + any live drops{'\n'}
<span className="accent">[tool]</span> search_products(keyword:"warm")     <span className="text-bone-faint">→ 1 hit</span>{'\n'}
<span className="accent">[tool]</span> get_seckill_status()                <span className="text-bone-faint">→ 1 active</span>{'\n'}
{'\n'}
<span className="text-bone">ASSISTANT:</span> FTSM Hoodie · RM79 — best match.{'\n'}
           Limited Lanyard flash sale is <span className="accent">LIVE now</span>.<span className="animate-blink">_</span>
                </pre>
              </div>
            </ScrollReveal>
          </div>
        </section>

        {/* ===== CTA ===== */}
        <section className="mx-auto max-w-7xl px-6 py-20 md:py-28">
          <ScrollReveal className="flex flex-col items-start justify-between gap-8 md:flex-row md:items-end">
            <h2 className="font-display text-5xl font-extrabold uppercase leading-[0.9] tracking-tight md:text-7xl">
              Start<br />trading<span className="accent">.</span>
            </h2>
            <div className="flex flex-wrap gap-3">
              <Link to="/register" className="btn-spec">Create account &rarr;</Link>
              <Link to="/marketplace" className="btn-line">Browse as guest</Link>
            </div>
          </ScrollReveal>
        </section>

        {/* ===== Footer ===== */}
        <footer className="border-t hairline">
          <div className="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-2 px-6 py-6 mono text-[10px] uppercase tracking-[0.18em] text-bone-faint">
            <span>FTSM Campus Marketplace — FYP {new Date().getFullYear()}</span>
            <span>FTSM <span className="accent">//</span> UKM</span>
          </div>
        </footer>
      </div>
    </div>
  );
}
