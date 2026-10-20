import { describe, expect, it } from 'vitest';
import type { Field } from './model';
import { addField, children, normalize, problems, removeField, setItemType, slugify, updateField } from './tree';

const tree: Field[] = [
  { id: 'a', name: 'name', type: 'text' },
  { id: 'b', name: 'address', type: 'object', fields: [{ id: 'c', name: 'city', type: 'text' }] },
  { id: 'd', name: 'orders', type: 'list', items: { type: 'object', fields: [{ id: 'e', name: 'no', type: 'text' }] } },
];

describe('tree edits', () => {
  it('adds a child to an object and to list items', () => {
    const t1 = addField(tree, [1], { id: 'x', name: 'zip', type: 'text' });
    expect(children(t1[1])?.map((f) => f.name)).toEqual(['city', 'zip']);
    const t2 = addField(tree, [2], { id: 'y', name: 'total', type: 'number' });
    expect(t2[2].items?.fields?.map((f) => f.name)).toEqual(['no', 'total']);
    expect(tree[1].fields).toHaveLength(1); // original untouched
  });

  it('renames keep the id and nested updates reach the right field', () => {
    const t = updateField(tree, [2, 0], { name: 'number' });
    expect(t[2].items?.fields?.[0]).toMatchObject({ id: 'e', name: 'number' });
  });

  it('removes nested fields', () => {
    expect(children(removeField(tree, [1, 0])[1])).toEqual([]);
  });

  it('changing the type reshapes the field', () => {
    const toList = updateField(tree, [0], { type: 'list' });
    expect(toList[0].items).toEqual({ type: 'text' });
    const toObject = setItemType(toList, [0], 'object');
    expect(toObject[0].items).toEqual({ type: 'object', fields: [] });
    expect(normalize({ type: 'object', value: 'x' } as Field)).toEqual({ type: 'object', fields: [] });
  });

  it('reports missing and duplicate names with their path', () => {
    const bad: Field[] = [
      { id: '1', name: 'a', type: 'text' },
      { id: '2', name: 'a', type: 'text' },
      { id: '3', name: 'l', type: 'list', items: { type: 'object', fields: [{ id: '4', name: '', type: 'text' }] } },
    ];
    expect(problems(bad)).toEqual(['a: two fields with the same name', 'l[].(no name): give the field a name']);
  });

  it('slugify matches the engine', () => {
    expect(slugify('Register Person!')).toBe('register-person');
  });
});
