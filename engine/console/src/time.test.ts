import { describe, expect, it } from 'vitest';
import { shortWhen } from './time';

describe('shortWhen', () => {
  const now = new Date(2026, 10, 24, 18, 30);

  it('says today or yesterday with the time', () => {
    expect(shortWhen(new Date(2026, 10, 24, 18, 24), now, 'en-GB')).toBe('today, 18:24');
    expect(shortWhen(new Date(2026, 10, 24, 0, 5), now, 'en-GB')).toBe('today, 00:05');
    expect(shortWhen(new Date(2026, 10, 23, 23, 59), now, 'en-GB')).toBe('yesterday, 23:59');
  });

  it('gives the date alone further back', () => {
    expect(shortWhen(new Date(2026, 10, 3, 12, 0), now, 'en-GB')).toBe('3 Nov 2026');
  });
});
