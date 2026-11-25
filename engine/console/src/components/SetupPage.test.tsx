import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import type { ReactNode } from 'react';
import { SetupGate, SetupPage } from './SetupPage';

beforeAll(() => {
  // Mantine reads these; jsdom has neither.
  window.matchMedia ??= ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia;
  globalThis.ResizeObserver ??= class {
    observe() {}
    unobserve() {}
    disconnect() {}
  } as unknown as typeof ResizeObserver;
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

const wrap = (node: ReactNode) => render(<MantineProvider>{node}</MantineProvider>);

/** Mantine puts a required mark after the label text, so labels are matched by their start. */
function type(label: RegExp, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

const PASSWORD = /^Password(?! again)/;

describe('SetupPage', () => {
  it('creates the login only with a code and two matching, long enough passwords', async () => {
    const fetch = vi.fn(() => Promise.resolve(new Response('{"needed":false}', { status: 200 })));
    vi.stubGlobal('fetch', fetch);
    const done = vi.fn();
    wrap(<SetupPage minLength={12} onDone={done} />);
    const button = screen.getByRole('button', { name: 'Create login' }) as HTMLButtonElement;

    type(/^Setup code/, 'ABCD-EFGH');
    type(PASSWORD, 'short');
    expect(screen.getByText('Use at least 12 characters')).toBeTruthy();
    expect(button.disabled).toBe(true);

    type(PASSWORD, 'a-long-enough-password');
    type(/^Password again/, 'something-else-entirely');
    expect(screen.getByText('The passwords are not the same')).toBeTruthy();
    expect(button.disabled).toBe(true);

    type(/^Password again/, 'a-long-enough-password');
    expect(button.disabled).toBe(false);
    fireEvent.click(button);

    await waitFor(() => expect(done).toHaveBeenCalled());
    const [url, init] = fetch.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe('/admin/api/setup');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['Content-Type']).toBe('application/json');
    expect(JSON.parse(init.body as string)).toEqual({ code: 'ABCD-EFGH', user: 'admin', password: 'a-long-enough-password' });
  });

  it("shows the engine's message for a wrong code", async () => {
    vi.stubGlobal('fetch', () =>
      Promise.resolve(new Response('{"error":"wrong-setup-code","detail":"The setup code is not the one in the engine\'s log"}', { status: 403 })),
    );
    const done = vi.fn();
    wrap(<SetupPage minLength={12} onDone={done} />);
    type(/^Setup code/, 'WRONG');
    type(PASSWORD, 'a-long-enough-password');
    type(/^Password again/, 'a-long-enough-password');
    fireEvent.click(screen.getByRole('button', { name: 'Create login' }));
    expect(await screen.findByText("The setup code is not the one in the engine's log")).toBeTruthy();
    expect(done).not.toHaveBeenCalled();
  });
});

describe('SetupGate', () => {
  it('shows the setup page on a first start and the console otherwise', async () => {
    vi.stubGlobal('fetch', () => Promise.resolve(new Response('{"needed":true,"minPasswordLength":12}', { status: 200 })));
    wrap(<SetupGate>console</SetupGate>);
    expect(await screen.findByText('Create admin login')).toBeTruthy();
    expect(screen.queryByText('console')).toBeNull();
    cleanup();

    vi.stubGlobal('fetch', () => Promise.resolve(new Response('{"needed":false,"minPasswordLength":12}', { status: 200 })));
    wrap(<SetupGate>console</SetupGate>);
    expect(await screen.findByText('console')).toBeTruthy();
  });
});
