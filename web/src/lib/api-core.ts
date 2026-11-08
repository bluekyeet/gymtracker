/**
 * The website's API client, free of SvelteKit imports so it can be unit tested.
 *
 * - Sends the access token, and when the server answers 401 it trades the refresh token for a new
 *   pair once and repeats the request. Several requests failing at the same moment share one refresh:
 *   the server treats a refresh token used twice as theft.
 * - Empty responses (204) are fine; errors carry a readable message instead of "HTTP 401".
 */

export class ApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export interface TokenStorage {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

export interface ApiOptions {
  /** Origin of the API, e.g. `http://localhost:8081` (no trailing slash, no `/v1`). */
  baseUrl: string;
  storage: TokenStorage | null;
  fetch: typeof fetch;
}

interface TokenPair {
  accessToken: string;
  refreshToken: string;
}

const ACCESS_KEY = 'powertrack.accessToken';
const REFRESH_KEY = 'powertrack.refreshToken';

/** Accepts the origin or a URL ending in `/v1` (what compose passes) and returns just the origin. */
export function apiOrigin(raw: string | undefined, fallback = 'http://localhost:8081'): string {
  const value = (raw ?? '').trim().replace(/\/+$/, '').replace(/\/v1$/, '');
  return value === '' ? fallback : value;
}

const FRIENDLY_ERRORS: Record<string, string> = {
  invalid_credentials: 'Wrong email or password.',
  invalid_refresh: 'Your session has expired. Sign in again.',
  rate_limited: 'Too many attempts. Wait a minute and try again.',
  not_found: 'That no longer exists.',
  bad_request: 'The server did not understand that request.',
  internal_error: 'The server hit an error. Try again in a moment.'
};

async function messageFrom(res: Response): Promise<string> {
  try {
    const body = (await res.json()) as { error?: unknown };
    if (typeof body.error === 'string') return FRIENDLY_ERRORS[body.error] ?? body.error;
  } catch {
    /* no JSON body */
  }
  if (res.status === 429) return FRIENDLY_ERRORS.rate_limited;
  if (res.status >= 500) return 'The server is unavailable. Try again in a moment.';
  return `Request failed (${res.status}).`;
}

export function createApi(options: ApiOptions) {
  const { baseUrl, storage } = options;
  const doFetch = options.fetch;
  let refreshing: Promise<'ok' | 'rejected' | 'unavailable'> | null = null;

  const read = (key: string) => {
    try {
      return storage?.getItem(key) ?? null;
    } catch {
      return null;
    }
  };

  function saveSession(pair: TokenPair): void {
    storage?.setItem(ACCESS_KEY, pair.accessToken);
    storage?.setItem(REFRESH_KEY, pair.refreshToken);
  }

  function clearSession(): void {
    storage?.removeItem(ACCESS_KEY);
    storage?.removeItem(REFRESH_KEY);
  }

  function isSignedIn(): boolean {
    return read(ACCESS_KEY) !== null && read(REFRESH_KEY) !== null;
  }

  function send(path: string, init: RequestInit | undefined, withToken: boolean): Promise<Response> {
    const headers = new Headers(init?.headers);
    const token = read(ACCESS_KEY);
    if (withToken && token) headers.set('Authorization', `Bearer ${token}`);
    if (init?.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    return doFetch(`${baseUrl}${path}`, { ...init, headers });
  }

  function refreshSession(): Promise<'ok' | 'rejected' | 'unavailable'> {
    refreshing ??= (async () => {
      const refreshToken = read(REFRESH_KEY);
      if (!refreshToken) return 'rejected';
      let res: Response;
      try {
        res = await send('/v1/auth/refresh', { method: 'POST', body: JSON.stringify({ refreshToken }) }, false);
      } catch {
        return 'unavailable';
      }
      if (res.ok) {
        saveSession((await res.json()) as TokenPair);
        return 'ok';
      }
      // A 5xx or 429 says nothing about the token; keep it and let the user retry.
      return res.status >= 500 || res.status === 429 ? 'unavailable' : 'rejected';
    })().finally(() => {
      refreshing = null;
    });
    return refreshing;
  }

  async function request<T = unknown>(path: string, init?: RequestInit): Promise<T> {
    let res: Response;
    try {
      res = await send(path, init, true);
    } catch {
      throw new ApiError(0, 'Cannot reach the server. Check your connection.');
    }

    if (res.status === 401) {
      const outcome = await refreshSession();
      if (outcome === 'unavailable') throw new ApiError(503, 'The server is unavailable. Try again in a moment.');
      if (outcome === 'ok') {
        try {
          res = await send(path, init, true);
        } catch {
          throw new ApiError(0, 'Cannot reach the server. Check your connection.');
        }
      }
      if (outcome === 'rejected' || res.status === 401) {
        clearSession();
        throw new ApiError(401, 'Your session has expired. Sign in again.');
      }
    }

    if (!res.ok) throw new ApiError(res.status, await messageFrom(res));
    if (res.status === 204) return null as T;
    return (await res.json()) as T;
  }

  async function login(email: string, password: string): Promise<void> {
    let res: Response;
    try {
      res = await send(
        '/v1/auth/login',
        { method: 'POST', body: JSON.stringify({ email, password, deviceName: 'Website', deviceKind: 'web' }) },
        false
      );
    } catch {
      throw new ApiError(0, 'Cannot reach the server. Check your connection.');
    }
    if (!res.ok) throw new ApiError(res.status, await messageFrom(res));
    saveSession((await res.json()) as TokenPair);
  }

  return { request, login, logout: clearSession, isSignedIn };
}
