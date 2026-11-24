import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiError, UNREACHABLE, api } from './api';

afterEach(() => vi.unstubAllGlobals());

describe('request', () => {
  it('says Patchbay cannot be reached when no answer comes back, instead of the browser text', async () => {
    vi.stubGlobal('fetch', () => Promise.reject(new TypeError('Failed to fetch')));
    const failure = api.integrations();
    await expect(failure).rejects.toBeInstanceOf(ApiError);
    await expect(failure).rejects.toThrow(UNREACHABLE);
  });

  it("shows the engine's own detail for an answer that is an error", async () => {
    vi.stubGlobal('fetch', () =>
      Promise.resolve(new Response(JSON.stringify({ detail: 'No integration nope' }), { status: 404, statusText: 'Not Found' })),
    );
    await expect(api.integrations()).rejects.toThrow('No integration nope');
  });
});
