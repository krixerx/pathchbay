import type { FieldType } from './model';

/**
 * The linking rules (D-12), the same as the engine's LinkRules.java; both are tested against
 * rules/link-cases.json (R5). A path is dotted; a segment ending in [] means each item of that list, and a path
 * ending in [] is the list itself.
 */
export type ReasonCode =
  | 'list-to-single'
  | 'single-to-list'
  | 'upward-from-nested-list'
  | 'list-level-mismatch'
  | 'list-not-linked'
  | 'different-list';

export const REASON_TEXT: Record<ReasonCode, string> = {
  'list-to-single': 'A list can only be linked to a list',
  'single-to-list': "A single value can't be linked to a list",
  'upward-from-nested-list': "A field inside a nested list can't be linked to a less nested place",
  'list-level-mismatch': 'Lists can only be linked at the same nesting level',
  'list-not-linked': 'Link the surrounding list first',
  'different-list': 'The surrounding list is linked to a different list',
};

export interface ListLink {
  source: string;
  target: string;
}

const segments = (path: string) => path.split('.');
const isList = (path: string) => path.endsWith('[]');
const depth = (path: string) => segments(path).filter((s) => s.endsWith('[]')).length;

/** The path up to and including its `level`-th list segment (1-based). */
function listPrefix(path: string, level: number): string {
  const segs = segments(path);
  let seen = 0;
  for (let i = 0; i < segs.length; i++) {
    if (segs[i].endsWith('[]') && ++seen === level) return segs.slice(0, i + 1).join('.');
  }
  throw new Error(`${path} has no list level ${level}`);
}

/** Checks one link against the list links that exist; null when allowed. */
export function checkLink(source: string, target: string, listLinks: ListLink[]): ReasonCode | null {
  if (isList(source) !== isList(target)) return isList(source) ? 'list-to-single' : 'single-to-list';
  const listLink = isList(source);
  const k = depth(source);
  const m = depth(target);
  if (k > m) return 'upward-from-nested-list';
  if (listLink && k !== m) return 'list-level-mismatch';
  const surrounding = listLink ? m - 1 : m;
  for (let j = 1; j <= surrounding; j++) {
    const targetList = listPrefix(target, j);
    const linked = listLinks.find((l) => l.target === targetList);
    if (!linked) return 'list-not-linked';
    if (j <= (listLink ? k - 1 : k) && linked.source !== listPrefix(source, j)) return 'different-list';
  }
  return null;
}

/**
 * Whether a value of type `from` can go into `to` as it is (D-6). The engine copies values without converting them
 * (transforms are a later phase, TODO-T1), so only identical types, whole numbers into numbers and dates into text
 * are safe. Unknown types (the external side without a schema) are not judged.
 */
export function typesFit(from: FieldType | undefined, to: FieldType | undefined): boolean {
  if (!from || !to || from === to) return true;
  if (from === 'integer' && to === 'number') return true;
  if ((from === 'date' || from === 'datetime') && to === 'text') return true;
  if (from === 'list' || to === 'list' || from === 'object' || to === 'object') return true; // lists/objects: checked by checkLink
  return false;
}
