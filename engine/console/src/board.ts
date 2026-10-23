import type { ContractOperation, Field, FieldType, PayloadDefinition } from './model';
import { TYPE_LABELS } from './model';
import { checkLink, REASON_TEXT, typesFit } from './linkRules';
import type { LinkContext, ListLink } from './linkRules';

/**
 * The mapping board's model (D-2, D-4, D-6, D-7, D-11, D-12, D-20) as pure functions.
 * A row is one field on one side. `key` is what links store: a payload field id, or an external dotted path
 * (`body.title`, `path.id`, `[].title`). `rule` is the path used by the linking rules.
 */
export interface Row {
  key: string;
  label: string;
  type?: FieldType;
  required: boolean;
  constant: boolean;
  depth: number;
  group?: 'Path' | 'Query' | 'Headers' | 'Body' | 'Response';
  rule: string;
  isList: boolean;
  /** A header added on the board, not declared in the contract (D-27). */
  added?: boolean;
  /**
   * The type as the engine's link rules know it (D-29): set for payload fields and the whole answer, not for
   * external paths, whose type only comes from the contract.
   */
  ruleType?: FieldType;
}

/** The parts of a file (D-29) and their types, as the engine's ScriptGenerator.BLOB_PARTS. */
export const BLOB_PARTS: [string, FieldType][] = [
  ['data', 'text'],
  ['fileName', 'text'],
  ['contentType', 'text'],
  ['size', 'integer'],
];

export interface Link {
  from: string;
  to: string;
}

export type Tab = 'request' | 'response';

/**
 * Payload definition → rows keyed by field id; lists use `name[]` in their rule path, like the engine. A file
 * (blob) also gets one row per part, keyed `<field id>.<part>` (D-29).
 */
export function payloadRows(def: PayloadDefinition | null): Row[] {
  const rows: Row[] = [];
  const walk = (fields: Field[], prefix: string, depth: number) => {
    for (const f of fields) {
      const seg = f.type === 'list' ? `${f.name}[]` : (f.name ?? '');
      const rule = prefix ? `${prefix}.${seg}` : seg;
      const key = f.id ?? rule;
      rows.push({
        key,
        label: f.name ?? '',
        type: f.type,
        required: !!f.required,
        constant: f.value !== undefined && f.value !== null,
        depth,
        rule,
        isList: f.type === 'list',
        ruleType: f.type,
      });
      if (f.type === 'blob') rows.push(...blobPartRows(key, rule, depth + 1));
      const kids = f.type === 'object' ? f.fields : f.type === 'list' && f.items?.type === 'object' ? f.items.fields : undefined;
      if (kids) walk(kids, rule, depth + 1);
    }
  };
  walk(def?.fields ?? [], '', 0);
  return rows;
}

/** One row per part of a file: `key`/`rule` are the file's, followed by `.data`, `.fileName`, … (D-29). */
function blobPartRows(key: string, rule: string, depth: number, group?: Row['group']): Row[] {
  return BLOB_PARTS.map(([part, type]) => ({
    key: `${key}.${part}`,
    label: part,
    type,
    required: false,
    constant: false,
    depth,
    group,
    rule: `${rule}.${part}`,
    isList: false,
    ruleType: type,
  }));
}

/** A contract field tree → rows keyed by dotted external path; `root` is '' for a response, 'body' for a request body. */
function treeRows(field: Field | undefined, root: string, group: Row['group'], baseDepth: number): Row[] {
  if (!field) return [];
  const rows: Row[] = [];
  const walk = (f: Field, path: string, depth: number, label: string) => {
    if (f.type === 'object') {
      if (label) rows.push(row(path, label, f, depth, group));
      for (const c of f.fields ?? []) walk(c, path ? `${path}.${c.name}` : (c.name ?? ''), label ? depth + 1 : depth, c.name ?? '');
    } else if (f.type === 'list') {
      const listPath = path ? `${path}[]` : '[]';
      rows.push(row(listPath, label || 'each item of the response', f, depth, group));
      const item = f.items;
      if (item?.type === 'object') for (const c of item.fields ?? []) walk(c, `${listPath}.${c.name}`, depth + 1, c.name ?? '');
    } else {
      rows.push(row(path, label, f, depth, group));
    }
  };
  if (root === 'body' && field.type === 'object') {
    for (const c of field.fields ?? []) walk(c, `body.${c.name}`, baseDepth, c.name ?? '');
  } else if (root === 'body') {
    walk(field, 'body', baseDepth, 'body');
  } else {
    walk(field, '', baseDepth, '');
  }
  return rows;
}

function row(key: string, label: string, f: Field, depth: number, group: Row['group']): Row {
  return { key, label, type: f.type, required: !!f.required, constant: false, depth, group, rule: key, isList: key.endsWith('[]') };
}

/**
 * The API request side, grouped Path / Query / Headers / Body (D-4); empty groups are left out. `added` are the
 * headers added on the board (D-27), listed after the contract's, without a type so any simple field fits.
 */
export function requestRows(op: ContractOperation | null, added: string[] = []): Row[] {
  if (!op) return [];
  const rows: Row[] = [];
  for (const [inKey, group] of [['path', 'Path'], ['query', 'Query'], ['header', 'Headers']] as const) {
    for (const p of op.parameters.filter((x) => x.in === inKey)) {
      const prefix = inKey === 'header' ? 'headers' : inKey;
      rows.push({
        key: `${prefix}.${p.name}`,
        label: p.name,
        type: p.type as FieldType,
        required: p.required,
        constant: false,
        depth: 0,
        group,
        rule: `${prefix}.${p.name}`,
        isList: false,
      });
    }
  }
  for (const name of added) {
    const key = `headers.${name}`;
    rows.push({ key, label: name, required: false, constant: false, depth: 0, group: 'Headers', rule: key, isList: false, added: true });
  }
  rows.push(...treeRows(op.requestBody, 'body', 'Body', 0));
  return rows;
}

/** The API response side; an answer that is a file is the row `$body` with its parts (D-29). */
export function responseRows(op: ContractOperation | null): Row[] {
  if (op?.response?.type === 'blob') {
    const whole: Row = {
      key: '$body',
      label: 'the whole answer (file)',
      type: 'blob',
      required: false,
      constant: false,
      depth: 0,
      group: 'Response',
      rule: '$body',
      isList: false,
      ruleType: 'blob',
    };
    return [whole, ...blobPartRows('$body', '$body', 1, 'Response')];
  }
  return treeRows(op?.response, '', 'Response', 0);
}

export type LinkState = { kind: 'ok' } | { kind: 'red'; reason: string } | { kind: 'broken'; reason: string };

/**
 * Each link's state (D-6, D-7, D-12): broken if an end no longer exists, red if rules or types don't allow it.
 * A side with no rows is one the contract doesn't describe (e.g. no response schema); only the other end is checked.
 * `requestFormat` is the draft's request format, which the file rules need (D-29).
 */
export function linkStates(links: Link[], sources: Row[], targets: Row[], requestFormat?: string): LinkState[] {
  if (sources.length === 0 || targets.length === 0) {
    const known = new Set((sources.length === 0 ? targets : sources).map((r) => r.key));
    return links.map((l) => {
      const end = sources.length === 0 ? l.to : l.from;
      return known.size === 0 || known.has(end) ? { kind: 'ok' } : { kind: 'broken', reason: `Field ${end} no longer exists` };
    });
  }
  const src = new Map(sources.map((r) => [r.key, r]));
  const tgt = new Map(targets.map((r) => [r.key, r]));
  const listLinks: ListLink[] = [];
  for (const l of links) {
    const s = src.get(l.from);
    const t = tgt.get(l.to);
    if (s && t && s.isList && t.isList) listLinks.push({ source: s.rule, target: t.rule });
  }
  return links.map((l) => {
    const s = src.get(l.from);
    const t = tgt.get(l.to);
    if (!s) return { kind: 'broken', reason: `Field ${l.from} no longer exists` };
    if (!t) return { kind: 'broken', reason: `Field ${l.to} no longer exists` };
    const reason = checkLink(s.rule, t.rule, listLinks, context(s, t, requestFormat));
    if (reason) return { kind: 'red', reason: REASON_TEXT[reason] };
    if (!typesFit(s.type, t.type)) return { kind: 'red', reason: typeReason(s.type, t.type) };
    return { kind: 'ok' };
  });
}

/** D-6 in plain words, with the same type names as everywhere else (D-4). */
function typeReason(from?: FieldType, to?: FieldType): string {
  const label = (t?: FieldType) => (t ? TYPE_LABELS[t] : 'unknown');
  return `A ${label(from)} value can't go into a ${label(to)} field`;
}

function context(source: Row, target: Row, requestFormat?: string): LinkContext {
  return { sourceType: source.ruleType, targetType: target.ruleType, requestFormat };
}

/** Whether a target row can take a link from the selected source, and why not (D-12 rule 6, D-6, D-29). */
export function canLink(source: Row, target: Row, links: Link[], sources: Row[], targets: Row[], requestFormat?: string): string | null {
  const others = links.filter((l) => l.to !== target.key);
  const states = linkStates(others, sources, targets, requestFormat);
  const listLinks: ListLink[] = [];
  others.forEach((l, i) => {
    const s = sources.find((r) => r.key === l.from);
    const t = targets.find((r) => r.key === l.to);
    if (s && t && s.isList && t.isList && states[i].kind === 'ok') listLinks.push({ source: s.rule, target: t.rule });
  });
  const reason = checkLink(source.rule, target.rule, listLinks, context(source, target, requestFormat));
  if (reason) return REASON_TEXT[reason];
  if (!typesFit(source.type, target.type)) return typeReason(source.type, target.type);
  return null;
}

/** Required target fields without a link (amber, D-6). Constants count as filled. */
export function requiredUnmapped(targets: Row[], links: Link[]): Row[] {
  const linked = new Set(links.map((l) => l.to));
  return targets.filter((r) => r.required && !r.constant && !linked.has(r.key));
}

/** Adds a link source → target, replacing an earlier link into the same target. */
export function addLink(links: Link[], from: string, to: string): Link[] {
  return [...links.filter((l) => l.to !== to), { from, to }];
}

export type Filter = 'all' | 'required' | 'unmapped' | 'mapped';

export function filterRows(rows: Row[], query: string, filter: Filter, linkedKeys: Set<string>): Row[] {
  const q = query.trim().toLowerCase();
  return rows.filter((r) => {
    if (q && !r.label.toLowerCase().includes(q) && !r.rule.toLowerCase().includes(q)) return false;
    if (filter === 'required') return r.required;
    if (filter === 'unmapped') return !linkedKeys.has(r.key);
    if (filter === 'mapped') return linkedKeys.has(r.key);
    return true;
  });
}

/** An example input for the Test drawer, built from the Input definition (constants are not sent). */
export function exampleInput(def: PayloadDefinition | null): unknown {
  const value = (f: Field): unknown => {
    switch (f.type) {
      case 'object':
        return Object.fromEntries((f.fields ?? []).filter((c) => c.value === undefined).map((c) => [c.name, value(c)]));
      case 'list':
        return f.items ? [value({ ...f.items, name: f.name })] : [];
      case 'integer':
        return 1;
      case 'number':
        return 1.5;
      case 'boolean':
        return true;
      case 'date':
        return '2026-01-01';
      case 'datetime':
        return '2026-01-01T10:00:00Z';
      case 'blob':
        return { fileName: 'example.txt', contentType: 'text/plain', data: 'ZXhhbXBsZQ==' };
      default:
        return f.name ?? 'text';
    }
  };
  return value({ type: 'object', fields: def?.fields ?? [] });
}
