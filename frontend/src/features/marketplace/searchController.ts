import type { SearchFilters, SearchHit, SearchParams, SearchResponse } from '../../types';

// Debounced, cancellable, race-free catalogue search (docs/phases/P5a.md §6.6). Framework-free so it can be tested
// with fake timers; Marketplace.tsx wires it to React state.

export type SearchState = {
  status: 'idle' | 'loading' | 'results' | 'empty' | 'error';
  hits: SearchHit[];
  error?: string;
};

export type Filters = SearchFilters;

const isAbort = (e: unknown) => {
  const err = e as { name?: string; code?: string } | null;
  return err?.name === 'AbortError' || err?.name === 'CanceledError' || err?.code === 'ERR_CANCELED';
};

const messageOf = (e: unknown) => {
  const err = e as { response?: { data?: { message?: string } }; message?: string } | null;
  return err?.response?.data?.message ?? err?.message ?? 'Search failed';
};

export function createSearchController(
  fetcher: (p: SearchParams, s: AbortSignal) => Promise<SearchResponse>,
  onState: (s: SearchState) => void,
  debounceMs = 300,
): { setQuery(q: string, filters?: Filters): void; dispose(): void } {
  let timer: ReturnType<typeof setTimeout> | undefined;
  let inFlight: AbortController | null = null;
  let seq = 0;              // only the response of the latest request is used
  let disposed = false;

  const cancel = () => {
    if (timer !== undefined) clearTimeout(timer);
    timer = undefined;
    inFlight?.abort();
    inFlight = null;
  };

  const run = async (q: string, filters: Filters | undefined, my: number) => {
    inFlight?.abort();
    const ctrl = new AbortController();
    inFlight = ctrl;
    onState({ status: 'loading', hits: [] });
    try {
      const res = await fetcher({ q, ...filters }, ctrl.signal);
      if (my !== seq || disposed) return;
      onState(res.hits.length ? { status: 'results', hits: res.hits } : { status: 'empty', hits: [] });
    } catch (e) {
      if (my !== seq || disposed || ctrl.signal.aborted || isAbort(e)) return;
      onState({ status: 'error', hits: [], error: messageOf(e) });
    } finally {
      if (inFlight === ctrl) inFlight = null;
    }
  };

  return {
    setQuery(q: string, filters?: Filters) {
      if (disposed) return;
      cancel();
      const my = ++seq;
      const trimmed = q.trim();
      if (!trimmed) {
        onState({ status: 'idle', hits: [] });
        return;
      }
      timer = setTimeout(() => {
        timer = undefined;
        void run(trimmed, filters, my);
      }, debounceMs);
    },
    dispose() {
      disposed = true;
      seq++;
      cancel();
    },
  };
}
