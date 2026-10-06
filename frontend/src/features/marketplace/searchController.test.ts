import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createSearchController, type SearchState } from './searchController';
import type { SearchHit, SearchParams, SearchResponse } from '../../types';

const hit = (id: number): SearchHit => ({
  ref: `product:${id}`, type: 'product', id, title: `p${id}`, price: 10, category: 'Apparel', imageUrl: null, score: 1,
});
const response = (q: string, hits: SearchHit[]): SearchResponse => ({ query: q, effectiveMode: 'KEYWORD', hits });

/** A fetcher whose calls resolve or reject only when the test says so. */
function controllableFetcher() {
  const calls: { params: SearchParams; signal: AbortSignal; resolve: (r: SearchResponse) => void; reject: (e: unknown) => void }[] = [];
  const fetcher = vi.fn((params: SearchParams, signal: AbortSignal) => new Promise<SearchResponse>((resolve, reject) => {
    calls.push({ params, signal, resolve, reject });
  }));
  return { fetcher, calls };
}

describe('createSearchController', () => {
  let states: SearchState[];
  const onState = (s: SearchState) => states.push(s);
  const last = () => states[states.length - 1];

  beforeEach(() => {
    vi.useFakeTimers();
    states = [];
  });
  afterEach(() => vi.useRealTimers());

  it('debounces typing and sends one request 300 ms after the last keystroke', async () => {
    const { fetcher, calls } = controllableFetcher();
    const c = createSearchController(fetcher, onState);
    c.setQuery('h');
    await vi.advanceTimersByTimeAsync(200);
    c.setQuery('ho');
    await vi.advanceTimersByTimeAsync(200);
    c.setQuery('hoodie ', { maxPrice: 50, category: 'Apparel' });
    await vi.advanceTimersByTimeAsync(299);
    expect(fetcher).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(fetcher).toHaveBeenCalledTimes(1);
    expect(calls[0].params).toEqual({ q: 'hoodie', maxPrice: 50, category: 'Apparel' });
    expect(last().status).toBe('loading');

    calls[0].resolve(response('hoodie', [hit(1001)]));
    await vi.runAllTimersAsync();
    expect(last()).toEqual({ status: 'results', hits: [hit(1001)] });
  });

  it('aborts the previous request when a new query is sent', async () => {
    const { fetcher, calls } = controllableFetcher();
    const c = createSearchController(fetcher, onState);
    c.setQuery('hoodie');
    await vi.advanceTimersByTimeAsync(300);
    c.setQuery('kasut');
    expect(calls[0].signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(300);
    expect(calls).toHaveLength(2);
    expect(calls[1].signal.aborted).toBe(false);
  });

  it('ignores a stale response that arrives after a newer one', async () => {
    const { fetcher, calls } = controllableFetcher();
    const c = createSearchController(fetcher, onState);
    c.setQuery('hoodie');
    await vi.advanceTimersByTimeAsync(300);
    c.setQuery('kasut');
    await vi.advanceTimersByTimeAsync(300);

    calls[1].resolve(response('kasut', [hit(2)]));
    await vi.runAllTimersAsync();
    calls[0].resolve(response('hoodie', [hit(1)]));   // late, and its signal was aborted
    await vi.runAllTimersAsync();

    expect(last()).toEqual({ status: 'results', hits: [hit(2)] });
    expect(states.filter((s) => s.status === 'results')).toHaveLength(1);
  });

  it('returns to idle without a request when the trimmed input is empty', async () => {
    const { fetcher, calls } = controllableFetcher();
    const c = createSearchController(fetcher, onState);
    c.setQuery('hoodie');
    await vi.advanceTimersByTimeAsync(300);
    c.setQuery('   ');
    expect(last()).toEqual({ status: 'idle', hits: [] });
    expect(calls[0].signal.aborted).toBe(true);
    await vi.advanceTimersByTimeAsync(1000);
    expect(fetcher).toHaveBeenCalledTimes(1);
  });

  it('shows empty when there are no hits', async () => {
    const { fetcher, calls } = controllableFetcher();
    const c = createSearchController(fetcher, onState);
    c.setQuery('zzzz');
    await vi.advanceTimersByTimeAsync(300);
    calls[0].resolve(response('zzzz', []));
    await vi.runAllTimersAsync();
    expect(last()).toEqual({ status: 'empty', hits: [] });
  });

  it('goes to error with the server message on HTTP 400', async () => {
    const { fetcher, calls } = controllableFetcher();
    const c = createSearchController(fetcher, onState);
    c.setQuery('hoodie', { category: 'Gadgets' });
    await vi.advanceTimersByTimeAsync(300);
    calls[0].reject({ response: { status: 400, data: { message: 'category must be one of: Apparel, …' } } });
    await vi.runAllTimersAsync();
    expect(last()).toEqual({ status: 'error', hits: [], error: 'category must be one of: Apparel, …' });
  });

  it('goes to error on a network failure', async () => {
    const { fetcher, calls } = controllableFetcher();
    const c = createSearchController(fetcher, onState);
    c.setQuery('hoodie');
    await vi.advanceTimersByTimeAsync(300);
    calls[0].reject(new Error('Network Error'));
    await vi.runAllTimersAsync();
    expect(last()).toEqual({ status: 'error', hits: [], error: 'Network Error' });
  });

  it('does not report an aborted request as an error', async () => {
    const { fetcher, calls } = controllableFetcher();
    const c = createSearchController(fetcher, onState);
    c.setQuery('hoodie');
    await vi.advanceTimersByTimeAsync(300);
    c.setQuery('kasut');
    calls[0].reject({ name: 'CanceledError', code: 'ERR_CANCELED', message: 'canceled' });
    await vi.runAllTimersAsync();
    expect(states.some((s) => s.status === 'error')).toBe(false);

    c.dispose();
    calls[1]?.reject(new DOMException('aborted', 'AbortError'));
    await vi.runAllTimersAsync();
    expect(states.some((s) => s.status === 'error')).toBe(false);
  });
});
