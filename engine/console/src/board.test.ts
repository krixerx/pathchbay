import { describe, expect, it } from 'vitest';
import type { ContractOperation, PayloadDefinition } from './model';
import { addLink, canLink, exampleInput, filterRows, linkStates, payloadRows, requestRows, requiredUnmapped, responseRows } from './board';

const person: PayloadDefinition = {
  id: 'person',
  name: 'Person',
  fields: [
    { id: 'p-name', name: 'name', type: 'text', required: true },
    { id: 'p-born', name: 'born', type: 'date' },
    { id: 'p-country', name: 'country', type: 'text', value: 'EE' },
    {
      id: 'p-addresses',
      name: 'addresses',
      type: 'list',
      items: { type: 'object', fields: [{ id: 'p-city', name: 'city', type: 'text' }] },
    },
  ],
};

const op: ContractOperation = {
  id: 'createPerson',
  method: 'POST',
  path: '/people/{tenant}',
  parameters: [
    { name: 'tenant', in: 'path', required: true, type: 'text' },
    { name: 'X-Trace', in: 'header', required: false, type: 'text' },
  ],
  requestBody: {
    type: 'object',
    fields: [
      { name: 'fullName', type: 'text', required: true },
      { name: 'age', type: 'integer' },
      { name: 'ADDRESSES', type: 'list', items: { type: 'object', fields: [{ name: 'town', type: 'text' }] } },
    ],
  },
  response: { type: 'list', items: { type: 'object', fields: [{ name: 'id', type: 'integer' }] } },
};

const src = payloadRows(person);
const tgt = requestRows(op);

describe('rows', () => {
  it('payload rows use field ids as keys and name[] in rule paths', () => {
    expect(src.map((r) => [r.key, r.rule, r.depth])).toEqual([
      ['p-name', 'name', 0],
      ['p-born', 'born', 0],
      ['p-country', 'country', 0],
      ['p-addresses', 'addresses[]', 0],
      ['p-city', 'addresses[].city', 1],
    ]);
  });

  it('request rows are grouped Path / Headers / Body with dotted keys', () => {
    expect(tgt.map((r) => [r.group, r.key])).toEqual([
      ['Path', 'path.tenant'],
      ['Headers', 'headers.X-Trace'],
      ['Body', 'body.fullName'],
      ['Body', 'body.age'],
      ['Body', 'body.ADDRESSES[]'],
      ['Body', 'body.ADDRESSES[].town'],
    ]);
  });

  it('a response that is a list starts with []', () => {
    expect(responseRows(op).map((r) => r.key)).toEqual(['[]', '[].id']);
  });
});

describe('links', () => {
  it('ok, red by rule, red by type, and broken', () => {
    const links = [
      { from: 'p-name', to: 'body.fullName' },
      { from: 'p-city', to: 'body.ADDRESSES[].town' }, // list not linked yet
      { from: 'p-born', to: 'body.age' }, // date into whole number
      { from: 'p-gone', to: 'body.fullName' },
    ];
    expect(linkStates(links, src, tgt).map((s) => s.kind)).toEqual(['ok', 'red', 'red', 'broken']);
  });

  it('links to a side the contract does not describe are not judged', () => {
    const links = [
      { from: 'id', to: 'p-name' },
      { from: 'id', to: 'p-gone' }, // the payload end is still checked
    ];
    expect(linkStates(links, [], src).map((s) => s.kind)).toEqual(['ok', 'broken']);
  });

  it('item links become ok once the lists are linked', () => {
    const links = [
      { from: 'p-addresses', to: 'body.ADDRESSES[]' },
      { from: 'p-city', to: 'body.ADDRESSES[].town' },
      { from: 'p-country', to: 'body.ADDRESSES[].town' },
    ];
    expect(linkStates(links.slice(0, 2), src, tgt).map((s) => s.kind)).toEqual(['ok', 'ok']);
    expect(linkStates([links[0], links[2]], src, tgt)[1].kind).toBe('ok'); // outer field into each item
  });

  it('canLink explains why a target is not linkable', () => {
    const city = src.find((r) => r.key === 'p-city')!;
    const town = tgt.find((r) => r.key === 'body.ADDRESSES[].town')!;
    expect(canLink(city, town, [], src, tgt)).toBe('Link the surrounding list first');
    expect(canLink(city, town, [{ from: 'p-addresses', to: 'body.ADDRESSES[]' }], src, tgt)).toBeNull();
  });

  it('a new link into the same target replaces the old one', () => {
    expect(addLink([{ from: 'a', to: 'x' }, { from: 'b', to: 'y' }], 'c', 'x')).toEqual([
      { from: 'b', to: 'y' },
      { from: 'c', to: 'x' },
    ]);
  });

  it('required targets without a link are reported', () => {
    expect(requiredUnmapped(tgt, [{ from: 'p-name', to: 'body.fullName' }]).map((r) => r.key)).toEqual(['path.tenant']);
  });
});

describe('filters and example input', () => {
  it('filters by text and by mapped state', () => {
    const linked = new Set(['body.fullName']);
    expect(filterRows(tgt, 'town', 'all', linked).map((r) => r.key)).toEqual(['body.ADDRESSES[].town']);
    expect(filterRows(tgt, '', 'mapped', linked).map((r) => r.key)).toEqual(['body.fullName']);
    expect(filterRows(tgt, '', 'required', linked).map((r) => r.key)).toEqual(['path.tenant', 'body.fullName']);
  });

  it('example input leaves constants out', () => {
    expect(exampleInput(person)).toEqual({ name: 'name', born: '2026-01-01', addresses: [{ city: 'city' }] });
  });
});
