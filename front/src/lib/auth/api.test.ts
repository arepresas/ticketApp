import { describe, expect, it, vi, beforeEach } from 'vitest';
import { AuthApiError, exchangeIdToken, fetchMe, logout } from './api';
import type { SessionResponse, AuthUser } from './types';

const mockFetch = (responder: (input: RequestInfo | URL, init?: RequestInit) => Promise<Response>): void => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(responder);
};

const jsonResponse = (status: number, body: unknown): Response =>
  new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' }
  });

const user: AuthUser = { id: 'sub-1', email: 'a@b.c', name: 'Alice' };
const session: SessionResponse = { token: 'bff.jwt.token', user };

describe('auth/api', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  describe('exchangeIdToken', () => {
    it('POSTs idToken to /api/auth/google and returns the session', async () => {
      mockFetch(async (input, init) => {
        expect(input).toBe('/api/auth/google');
        expect(init?.method).toBe('POST');
        expect(JSON.parse(init?.body as string)).toEqual({ idToken: 'id.jwt.x' });
        return jsonResponse(200, session);
      });

      const got = await exchangeIdToken('id.jwt.x');
      expect(got).toEqual(session);
    });

    it('throws AuthApiError on 4xx with parsed message', async () => {
      mockFetch(async () => jsonResponse(401, { message: 'invalid_token' }));

      await expect(exchangeIdToken('bad')).rejects.toMatchObject({
        name: 'AuthApiError',
        status: 401,
        message: 'invalid_token'
      });
    });

    it('falls back to HTTP status when error body is not JSON', async () => {
      mockFetch(async () => new Response('oops', { status: 500 }));

      await expect(exchangeIdToken('bad')).rejects.toMatchObject({
        status: 500,
        message: 'HTTP 500'
      });
    });
  });

  describe('fetchMe', () => {
    it('sends Bearer token and returns the user', async () => {
      mockFetch(async (input, init) => {
        expect(input).toBe('/api/auth/me');
        expect(init?.headers).toMatchObject({ authorization: 'Bearer xxx' });
        return jsonResponse(200, user);
      });

      expect(await fetchMe('xxx')).toEqual(user);
    });

    it('throws on non-ok', async () => {
      mockFetch(async () => jsonResponse(403, { error: 'forbidden' }));
      await expect(fetchMe('xxx')).rejects.toBeInstanceOf(AuthApiError);
    });
  });

  describe('logout', () => {
    it('does not throw on 401 (token already invalid)', async () => {
      mockFetch(async () => new Response('', { status: 401 }));
      await expect(logout('xxx')).resolves.toBeUndefined();
    });

    it('sends POST with Bearer token on success', async () => {
      const calls: Array<[RequestInfo | URL, RequestInit | undefined]> = [];
      mockFetch(async (input, init) => {
        calls.push([input, init]);
        return new Response(null, { status: 204 });
      });

      await logout('yyy');
      expect(calls[0][0]).toBe('/api/auth/logout');
      expect(calls[0][1]?.method).toBe('POST');
      expect(calls[0][1]?.headers).toMatchObject({ authorization: 'Bearer yyy' });
    });
  });
});