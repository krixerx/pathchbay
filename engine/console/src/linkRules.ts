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
  | 'different-list'
  | 'whole-body-needs-blob'
  | 'whole-body-needs-binary'
  | 'blob-outside-body'
  | 'multipart-not-flat'
  | 'whole-answer-needs-blob'
  | 'blob-to-simple';

export const REASON_TEXT: Record<ReasonCode, string> = {
  'list-to-single': 'A list can only be linked to a list',
  'single-to-list': "A single value can't be linked to a list",
  'upward-from-nested-list': "A field inside a nested list can't be linked to a less nested place",
  'list-level-mismatch': 'Lists can only be linked at the same nesting level',
  'list-not-linked': 'Link the surrounding list first',
  'different-list': 'The surrounding list is linked to a different list',
  'whole-body-needs-blob': 'Only a file (blob) can be the whole body',
  'whole-body-needs-binary': 'A file as the whole body needs the request format binary',
  'blob-outside-body': 'A file can only go into the body',
  'multipart-not-flat': 'Multipart parts are single fields: body.<name>, no lists or objects',
  'whole-answer-needs-blob': 'The whole answer can only go into a file (blob)',
  'blob-to-simple': 'A file only fits a file; link its data (base64), fileName or contentType instead',
};

/**
 * What the file rules (D-29) need besides the paths: the ends' types where the engine knows them (payload fields
 * and the whole answer, not external paths) and the request format (json when absent).
 */
export interface LinkContext {
  sourceType?: FieldType;
  targetType?: FieldType;
  requestFormat?: string;
}

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

/** Checks one link against the list links that exist, then the file rules; null when allowed. */
export function checkLink(source: string, target: string, listLinks: ListLink[], ctx: LinkContext = {}): ReasonCode | null {
  return checkLists(source, target, listLinks) ?? checkFiles(source, target, ctx);
}

function checkFiles(source: string, target: string, { sourceType, targetType, requestFormat }: LinkContext): ReasonCode | null {
  const sourceBlob = sourceType === 'blob';
  const targetBlob = targetType === 'blob';
  const t = segments(target);
  if (target === 'body') {
    if (!sourceBlob) return 'whole-body-needs-blob';
    return requestFormat === 'binary' ? null : 'whole-body-needs-binary';
  }
  if (sourceBlob && ['path', 'query', 'headers'].includes(t[0])) return 'blob-outside-body';
  if (requestFormat === 'multipart' && t[0] === 'body' && (t.length !== 2 || isList(target))) return 'multipart-not-flat';
  if (source === '$body' && !targetBlob) return 'whole-answer-needs-blob';
  if (sourceType && targetType && sourceBlob !== targetBlob) return 'blob-to-simple';
  return null;
}

function checkLists(source: string, target: string, listLinks: ListLink[]): ReasonCode | null {
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
