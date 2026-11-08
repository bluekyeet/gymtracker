import { describe, expect, it } from 'vitest';
import { ApiError, apiOrigin, createApi, type TokenStorage } from './api-core';

function memoryStorage(initial: Record<string, string> = {}): TokenStorage & { data: Record<string, string> } {
  const data = { ...initial };
  return {
    data,
    getItem: (k) => data[k] ?? null,
    setItem: (k, v) => {
      data[k] = v;
    },
    removeItem: (k) => {
      delete data[k];
    }
  };
}

const signedIn = () =>
  memoryStorage({ 'powertrack.accessToken': 'old-access', 'powertrack.refreshToken': 'old-refresh' });

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

function setup(handler: Handler, storage = signedIn()) {
  const calls: { url: string; auth: string | null; body: string | null }[] = [];
  const fetchFn = (async (url: string, init: RequestInit = {}) => {
    const headers = new Headers(init.headers);
    calls.push({ url, auth: headers.get('Authorization'), body: (init.body as string) ?? null });
    return handler(url, init);
  }) as unknown as typeof fetch;
  const api = createApi({ baseUrl: 'http://api.test', storage, fetch: fetchFn });
  return { api, calls, storage };
}

describe('apiOrigin', () => {
  it('strips a trailing /v1 and slashes, and falls back when unset', () => {
    expect(apiOrigin('http://localhost:8081/v1')).toBe('http://localhost:8081');
    expect(apiOrigin('https://api.gym.example.com/')).toBe('https://api.gym.example.com');
    expect(apiOrigin('https://api.gym.example.com/v1/')).toBe('https://api.gym.example.com');
    expect(apiOrigin(undefined)).toBe('http://localhost:8081');
    expect(apiOrigin('  ')).toBe('http://localhost:8081');
  });
});

describe('request', () => {
  it('sends the bearer token to the configured origin', async () => {
    const { api, calls } = setup(() => json({ ok: true }));
    await api.request('/v1/devices');
    expect(calls[0]).toMatchObject({ url: 'http://api.test/v1/devices', auth: 'Bearer old-access' });
  });

  it('treats 204 No Content as success (revoking a device used to throw)', async () => {
    const { api } = setup(() => new Response(null, { status: 204 }));
    await expect(api.request('/v1/devices/x/revoke', { method: 'POST' })).resolves.toBeNull();
  });

  it('refreshes once on 401 and repeats the request with the new token', async () => {
    const { api, calls, storage } = setup((url) => {
      if (url.endsWith('/v1/auth/refresh')) return json({ accessToken: 'new-access', refreshToken: 'new-refresh' });
      return calls.at(-1)!.auth === 'Bearer old-access' ? json({}, 401) : json({ ok: 1 });
    });
    await expect(api.request('/v1/devices')).resolves.toEqual({ ok: 1 });
    expect(calls.map((c) => c.url.replace('http://api.test', ''))).toEqual(['/v1/devices', '/v1/auth/refresh', '/v1/devices']);
    expect(calls[1].auth).toBeNull(); // the refresh call carries no stale bearer
    expect(JSON.parse(calls[1].body!)).toEqual({ refreshToken: 'old-refresh' });
    expect(storage.data['powertrack.refreshToken']).toBe('new-refresh');
  });

  it('shares one refresh between simultaneous 401s (a token used twice reads as theft)', async () => {
    let refreshes = 0;
    const { api } = setup(async (url, init) => {
      if (url.endsWith('/v1/auth/refresh')) {
        refreshes++;
        await new Promise((r) => setTimeout(r, 10));
        return json({ accessToken: 'new-access', refreshToken: 'new-refresh' });
      }
      return new Headers(init.headers).get('Authorization') === 'Bearer new-access' ? json({ ok: 1 }) : json({}, 401);
    });
    const results = await Promise.all([api.request('/v1/a'), api.request('/v1/b'), api.request('/v1/c')]);
    expect(results).toEqual([{ ok: 1 }, { ok: 1 }, { ok: 1 }]);
    expect(refreshes).toBe(1);
  });

  it('signs out when the refresh token is refused', async () => {
    const { api, storage } = setup((url) => (url.endsWith('/refresh') ? json({ error: 'invalid_refresh' }, 401) : json({}, 401)));
    const err = await api.request('/v1/devices').catch((e: unknown) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect((err as ApiError).status).toBe(401);
    expect((err as ApiError).message).toMatch(/Sign in again/);
    expect(api.isSignedIn()).toBe(false);
    expect(storage.data).toEqual({});
  });

  it('keeps the session when the server is merely down during a refresh', async () => {
    const { api } = setup((url) => (url.endsWith('/refresh') ? json({}, 503) : json({}, 401)));
    const err = (await api.request('/v1/devices').catch((e: unknown) => e)) as ApiError;
    expect(err.status).toBe(503);
    expect(api.isSignedIn()).toBe(true);
  });

  it('reports an unreachable server in plain words', async () => {
    const { api } = setup(() => {
      throw new TypeError('fetch failed');
    });
    const err = (await api.request('/v1/devices').catch((e: unknown) => e)) as ApiError;
    expect(err.message).toMatch(/Cannot reach the server/);
  });

  it('turns server error codes into readable messages', async () => {
    const { api } = setup(() => json({ error: 'rate_limited' }, 429));
    await expect(api.request('/v1/x')).rejects.toThrow(/Wait a minute/);
  });
});

describe('login', () => {
  it('stores both tokens and sends no stale bearer', async () => {
    const { api, calls, storage } = setup(() => json({ accessToken: 'a', refreshToken: 'r', deviceId: 'd' }), memoryStorage());
    await api.login('me@example.com', 'pw');
    expect(calls[0].auth).toBeNull();
    expect(JSON.parse(calls[0].body!)).toMatchObject({ email: 'me@example.com', deviceKind: 'web' });
    expect(storage.data).toEqual({ 'powertrack.accessToken': 'a', 'powertrack.refreshToken': 'r' });
    expect(api.isSignedIn()).toBe(true);
  });

  it('a wrong password is a readable error, not a session expiry', async () => {
    const { api } = setup(() => json({ error: 'invalid_credentials' }, 401), memoryStorage());
    await expect(api.login('me@example.com', 'nope')).rejects.toThrow('Wrong email or password.');
    expect(api.isSignedIn()).toBe(false);
  });

  it('logout forgets both tokens', () => {
    const { api } = setup(() => json({}));
    api.logout();
    expect(api.isSignedIn()).toBe(false);
  });
});
