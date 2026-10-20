import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { checkLink, typesFit } from './linkRules';

// The same table the engine's LinkCasesTest runs (R5).
const file = JSON.parse(readFileSync(resolve(__dirname, '../../../rules/link-cases.json'), 'utf-8')) as {
  cases: { name: string; source: string; target: string; listLinks: [string, string][]; reason: string | null }[];
};

describe('shared link cases (rules/link-cases.json)', () => {
  it.each(file.cases.map((c) => [c.name, c] as const))('%s', (_name, c) => {
    const listLinks = c.listLinks.map(([source, target]) => ({ source, target }));
    expect(checkLink(c.source, c.target, listLinks)).toBe(c.reason);
  });
});

describe('typesFit', () => {
  it('allows identical types and the safe widenings only', () => {
    expect(typesFit('text', 'text')).toBe(true);
    expect(typesFit('integer', 'number')).toBe(true);
    expect(typesFit('date', 'text')).toBe(true);
    expect(typesFit('number', 'integer')).toBe(false);
    expect(typesFit('date', 'number')).toBe(false);
    expect(typesFit('text', 'boolean')).toBe(false);
    expect(typesFit(undefined, 'text')).toBe(true);
  });
});
