import type { Field, FieldType } from './model';

/**
 * Pure edits on a definition's field tree. A path is the list of indices from the top; the children of an object
 * are its `fields`, the children of a list of objects are its item's `fields`.
 */
export type TreePath = number[];

export function children(f: Field): Field[] | undefined {
  if (f.type === 'object') return f.fields ?? [];
  if (f.type === 'list' && f.items?.type === 'object') return f.items.fields ?? [];
  return undefined;
}

function withChildren(f: Field, kids: Field[]): Field {
  if (f.type === 'object') return { ...f, fields: kids };
  return { ...f, items: { ...(f.items as Field), fields: kids } };
}

function edit(fields: Field[], path: TreePath, change: (siblings: Field[], index: number) => Field[]): Field[] {
  const [head, ...rest] = path;
  if (rest.length === 0) return change(fields, head);
  return fields.map((f, i) => (i === head ? withChildren(f, edit(children(f) ?? [], rest, change)) : f));
}

export function updateField(fields: Field[], path: TreePath, patch: Partial<Field>): Field[] {
  return edit(fields, path, (siblings, i) => siblings.map((f, j) => (j === i ? normalize({ ...f, ...patch }) : f)));
}

export function removeField(fields: Field[], path: TreePath): Field[] {
  return edit(fields, path, (siblings, i) => siblings.filter((_, j) => j !== i));
}

/** Adds a field at the top (empty path) or as the last child of the object or list-of-objects at `parent`. */
export function addField(fields: Field[], parent: TreePath, field: Field): Field[] {
  if (parent.length === 0) return [...fields, field];
  return edit(fields, parent, (siblings, i) =>
    siblings.map((f, j) => (j === i ? withChildren(f, [...(children(f) ?? []), field]) : f)),
  );
}

/** Keeps the shape consistent with the type: objects have fields, lists have an item type, constants are simple. */
export function normalize(f: Field): Field {
  const out: Field = { ...f };
  if (out.type === 'object') {
    out.fields = out.fields ?? [];
    delete out.items;
    delete out.value;
  } else if (out.type === 'list') {
    out.items = out.items ?? { type: 'text' };
    delete out.fields;
    delete out.value;
  } else {
    delete out.fields;
    delete out.items;
  }
  return out;
}

export function setItemType(fields: Field[], path: TreePath, type: FieldType): Field[] {
  return edit(fields, path, (siblings, i) =>
    siblings.map((f, j) => (j === i ? { ...f, items: type === 'object' ? { type, fields: [] } : { type } } : f)),
  );
}

let counter = 0;

/** A stable field id (D-18): never shown, never changed when the field is renamed. */
export function newFieldId(definitionId: string): string {
  counter += 1;
  return `${definitionId}-${Date.now().toString(36)}${counter.toString(36)}`;
}

/** Lower-case letters, digits and dashes, as the engine requires for ids. */
export function slugify(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
}

/** Problems the editor shows before saving; the engine checks again on save. */
export function problems(fields: Field[], at = ''): string[] {
  const out: string[] = [];
  const names = new Set<string>();
  for (const f of fields) {
    const where = at + (f.name || '(no name)');
    if (!f.name?.trim()) out.push(`${where}: give the field a name`);
    else if (names.has(f.name)) out.push(`${where}: two fields with the same name`);
    names.add(f.name ?? '');
    const kids = children(f);
    if (kids) out.push(...problems(kids, where + (f.type === 'list' ? '[].' : '.')));
  }
  return out;
}
