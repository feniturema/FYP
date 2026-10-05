import { afterEach, describe, expect, it, vi } from 'vitest';
import { createSseParser, streamAssistant, type SseEvent } from './sse';

const enc = new TextEncoder();

function parse(chunks: (string | Uint8Array)[], end = true): SseEvent[] {
  const events: SseEvent[] = [];
  const p = createSseParser((e) => events.push(e));
  for (const c of chunks) p.push(typeof c === 'string' ? enc.encode(c) : c);
  if (end) p.end();
  return events;
}

describe('createSseParser', () => {
  it('decodes a multi-byte UTF-8 character split across two chunks', () => {
    const bytes = enc.encode('event: token\ndata: 你好 RM\n\n');
    const cut = bytes.indexOf(0xe4) + 1; // inside the 3-byte encoding of 你
    expect(parse([bytes.slice(0, cut), bytes.slice(cut)])).toEqual([{ event: 'token', data: '你好 RM' }]);
  });

  it.each([
    ['\\n', '\n'],
    ['\\r\\n', '\r\n'],
    ['\\r', '\r'],
  ])('accepts %s line endings', (_name, nl) => {
    const text = `event: token${nl}data: a${nl}${nl}event: done${nl}data:${nl}${nl}`;
    expect(parse([text])).toEqual([
      { event: 'token', data: 'a' },
      { event: 'done', data: '' },
    ]);
  });

  it('counts a \\r\\n split across chunks as one line break', () => {
    expect(parse(['data: a\r', '\ndata: b\r', '\n\r', '\n'])).toEqual([{ event: 'message', data: 'a\nb' }]);
  });

  it('joins multi-line data with \\n and strips only one leading space', () => {
    expect(parse(['event: token\ndata:  two spaces\ndata:line2\ndata: \n\n'])).toEqual([
      { event: 'token', data: ' two spaces\nline2\n' },
    ]);
  });

  it('dispatches several events from one chunk', () => {
    expect(parse(['event:token\ndata:Hel\n\nevent:token\ndata:lo\n\nevent:done\ndata:\n\n'])).toEqual([
      { event: 'token', data: 'Hel' },
      { event: 'token', data: 'lo' },
      { event: 'done', data: '' },
    ]);
  });

  it('ignores comment lines', () => {
    expect(parse([': keep-alive\n\nevent: token\n: inner comment\ndata: x\n\n'])).toEqual([
      { event: 'token', data: 'x' },
    ]);
  });

  it('defaults the event name to message and drops an unterminated event at end()', () => {
    expect(parse(['data: first\n\nevent: token\ndata: unfinished\n'])).toEqual([{ event: 'message', data: 'first' }]);
  });
});

function sseResponse(body: ReadableStream<Uint8Array>, status = 200): Response {
  return new Response(body, { status, headers: { 'Content-Type': 'text/event-stream' } });
}

/** Delivers the chunks one read at a time, then closes (or fails, like a dropped connection). */
function streamOf(chunks: string[], fail?: Error): ReadableStream<Uint8Array> {
  let next = 0;
  return new ReadableStream({
    pull(controller) {
      if (next < chunks.length) controller.enqueue(enc.encode(chunks[next++]));
      else if (fail) controller.error(fail); // erroring in start() would discard chunks not yet read
      else controller.close();
    },
  });
}

function handlers() {
  return { onToken: vi.fn(), onDone: vi.fn(), onError: vi.fn() };
}

describe('streamAssistant', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('calls onToken for each token and onDone at the end', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      sseResponse(streamOf(['event:token\ndata:Hel\n\nevent:token\ndata:lo\n\n', 'event:done\ndata:\n\n'])),
    );
    vi.stubGlobal('fetch', fetchMock);
    const h = handlers();
    await streamAssistant('hi', 'conv-1', h, new AbortController().signal);
    expect(h.onToken.mock.calls.map((c) => c[0])).toEqual(['Hel', 'lo']);
    expect(h.onDone).toHaveBeenCalledOnce();
    expect(h.onError).not.toHaveBeenCalled();
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/assistant/stream');
    expect(JSON.parse(init.body)).toEqual({ message: 'hi', conversationId: 'conv-1' });
  });

  it('passes an error event to onError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      sseResponse(streamOf(['event:error\ndata:The assistant failed to answer. Please try again.\n\n'])),
    ));
    const h = handlers();
    await streamAssistant('hi', 'c', h, new AbortController().signal);
    expect(h.onError).toHaveBeenCalledWith('The assistant failed to answer. Please try again.');
    expect(h.onDone).not.toHaveBeenCalled();
  });

  it('reports a non-2xx status through onError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('denied', { status: 403 })));
    const h = handlers();
    await streamAssistant('hi', 'c', h, new AbortController().signal);
    expect(h.onError).toHaveBeenCalledWith('HTTP 403');
  });

  it('does not call onError when the caller aborts', async () => {
    const ctrl = new AbortController();
    vi.stubGlobal('fetch', vi.fn().mockImplementation((_url: string, init: RequestInit) => {
      const body = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(enc.encode('event:token\ndata:Hel\n\n'));
          init.signal?.addEventListener('abort', () =>
            controller.error(new DOMException('The operation was aborted.', 'AbortError')));
        },
      });
      return Promise.resolve(sseResponse(body));
    }));
    const h = handlers();
    const running = streamAssistant('hi', 'c', h, ctrl.signal);
    await vi.waitFor(() => expect(h.onToken).toHaveBeenCalledWith('Hel'));
    ctrl.abort();
    await running;
    expect(h.onError).not.toHaveBeenCalled();
    expect(h.onDone).not.toHaveBeenCalled();
  });

  it('does not call onError when fetch itself is aborted', async () => {
    const ctrl = new AbortController();
    ctrl.abort();
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new DOMException('aborted', 'AbortError')));
    const h = handlers();
    await streamAssistant('hi', 'c', h, ctrl.signal);
    expect(h.onError).not.toHaveBeenCalled();
  });

  it('calls onError when the connection drops mid-stream', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(
      sseResponse(streamOf(['event:token\ndata:Hel\n\n'], new TypeError('network error'))),
    ));
    const h = handlers();
    await streamAssistant('hi', 'c', h, new AbortController().signal);
    expect(h.onToken).toHaveBeenCalledWith('Hel');
    expect(h.onError).toHaveBeenCalledWith('connection closed');
  });

  it('calls onError when the stream ends without a done event', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(sseResponse(streamOf(['event:token\ndata:Hel\n\n']))));
    const h = handlers();
    await streamAssistant('hi', 'c', h, new AbortController().signal);
    expect(h.onError).toHaveBeenCalledWith('connection closed');
  });
});
