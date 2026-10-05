import { useAuthStore } from '../store/useAuthStore';

// Server-Sent Events over a POST fetch (EventSource cannot POST or send an Authorization header).
// docs/phases/P4b.md §6.9.

export interface SseEvent {
  event: string;
  data: string;
}

export interface AssistantHandlers {
  onToken(t: string): void;
  onDone(): void;
  onError(msg: string): void;
}

const API_BASE = import.meta.env.VITE_API_BASE_URL || '/api';

/**
 * Incremental SSE parser. Decodes UTF-8 across chunk boundaries, accepts \r\n, \n and \r line endings (a \r\n split
 * across two chunks counts once), ignores comment lines, strips one space after "data:", joins multi-line data with
 * \n, and dispatches on a blank line (event name defaults to "message"). end() drops an unterminated event.
 */
export function createSseParser(onEvent: (e: SseEvent) => void): { push(chunk: Uint8Array): void; end(): void } {
  const decoder = new TextDecoder('utf-8');
  let partialLine = '';
  let afterCr = false;
  let eventName = '';
  let dataLines: string[] = [];
  let sawField = false;

  const reset = () => {
    eventName = '';
    dataLines = [];
    sawField = false;
  };

  const onLine = (line: string) => {
    if (line === '') {
      if (sawField) onEvent({ event: eventName || 'message', data: dataLines.join('\n') });
      reset();
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    if (field === 'event') {
      eventName = value;
      sawField = true;
    } else if (field === 'data') {
      dataLines.push(value);
      sawField = true;
    }
    // id / retry / unknown fields are not used by the assistant stream.
  };

  const feed = (text: string) => {
    let start = 0;
    for (let i = 0; i < text.length; i++) {
      const c = text[i];
      if (afterCr) {
        afterCr = false;
        if (c === '\n') {
          start = i + 1;
          continue;
        }
      }
      if (c === '\r' || c === '\n') {
        onLine(partialLine + text.slice(start, i));
        partialLine = '';
        start = i + 1;
        afterCr = c === '\r';
      }
    }
    partialLine += text.slice(start);
  };

  return {
    push(chunk: Uint8Array) {
      feed(decoder.decode(chunk, { stream: true }));
    },
    end() {
      feed(decoder.decode());
      partialLine = '';
      afterCr = false;
      reset();
    },
  };
}

const isAbort = (e: unknown) => (e as { name?: string } | null)?.name === 'AbortError';

/** POST /api/assistant/stream and dispatch token / done / error events. An abort by the caller is silent. */
export async function streamAssistant(
  message: string,
  conversationId: string,
  handlers: AssistantHandlers,
  signal: AbortSignal,
): Promise<void> {
  const token = useAuthStore.getState().token;
  let res: Response;
  try {
    res = await fetch(`${API_BASE}/assistant/stream`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
      },
      body: JSON.stringify({ message, conversationId }),
      signal,
    });
  } catch (e) {
    if (!isAbort(e) && !signal.aborted) handlers.onError('network error');
    return;
  }

  if (res.status === 401) {
    useAuthStore.getState().logout();
    if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
      window.location.href = '/login';
    }
    return;
  }
  if (!res.ok || !res.body) {
    handlers.onError(`HTTP ${res.status}`);
    return;
  }

  let finished = false;
  const parser = createSseParser((e) => {
    if (finished) return;
    if (e.event === 'token') {
      handlers.onToken(e.data);
    } else if (e.event === 'done') {
      finished = true;
      handlers.onDone();
    } else if (e.event === 'error') {
      finished = true;
      handlers.onError(e.data || 'assistant error');
    }
  });

  const reader = res.body.getReader();
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      if (value) parser.push(value);
    }
    parser.end();
  } catch (e) {
    if (isAbort(e) || signal.aborted) return;
    if (!finished) handlers.onError('connection closed');
    return;
  }
  if (!finished && !signal.aborted) handlers.onError('connection closed');
}
