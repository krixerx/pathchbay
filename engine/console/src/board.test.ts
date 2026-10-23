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

  it('headers added on the board (D-27) follow the contract headers, untyped, so any simple field fits', () => {
    const rows = requestRows(op, ['X-Correlation-Id']);
    expect(rows.filter((r) => r.group === 'Headers').map((r) => [r.key, r.added ?? false])).toEqual([
      ['headers.X-Trace', false],
      ['headers.X-Correlation-Id', true],
    ]);
    const states = linkStates([{ from: 'p-born', to: 'headers.X-Correlation-Id' }], src, rows);
    expect(states).toEqual([{ kind: 'ok' }]);
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

  it('example input sends a blob as its JSON form', () => {
    const upload: PayloadDefinition = { id: 'upload', name: 'Upload', fields: [{ id: 'u-file', name: 'file', type: 'blob' }] };
    expect(exampleInput(upload)).toEqual({ file: { fileName: 'example.txt', contentType: 'text/plain', data: 'ZXhhbXBsZQ==' } });
  });

  // D-29: files on the board
  const upload: PayloadDefinition = { id: 'upload', name: 'Upload', fields: [{ id: 'u-file', name: 'file', type: 'blob' }] };
  const raw: ContractOperation = {
    id: 'replace',
    method: 'PUT',
    path: '/docs/{id}',
    parameters: [{ name: 'X-Name', in: 'header', required: false, type: 'text' }],
    requestBody: { name: 'body', type: 'blob' },
    response: { name: 'response', type: 'blob' },
  };

  it('a file has a row per part, keyed by field id and part', () => {
    const rows = payloadRows(upload);
    expect(rows.map((r) => [r.key, r.rule, r.type])).toEqual([
      ['u-file', 'file', 'blob'],
      ['u-file.data', 'file.data', 'text'],
      ['u-file.fileName', 'file.fileName', 'text'],
      ['u-file.contentType', 'file.contentType', 'text'],
      ['u-file.size', 'file.size', 'integer'],
    ]);
  });

  it('a file answer is the row $body with its parts', () => {
    expect(responseRows(raw).map((r) => r.key)).toEqual(['$body', '$body.data', '$body.fileName', '$body.contentType', '$body.size']);
  });

  it('the file rules use the request format and the known types', () => {
    const sources = payloadRows(upload);
    const targets = requestRows(raw);
    const file = sources[0];
    const body = targets.find((r) => r.key === 'body')!;
    const header = targets.find((r) => r.key === 'headers.X-Name')!;
    expect(canLink(file, body, [], sources, targets)).toBe('A file as the whole body needs the request format binary');
    expect(canLink(file, body, [], sources, targets, 'binary')).toBeNull();
    expect(canLink(file, header, [], sources, targets, 'binary')).toBe('A file can only go into the body');
    expect(canLink(sources[2], header, [], sources, targets, 'binary')).toBeNull();
    expect(linkStates([{ from: '$body', to: 'u-file' }], responseRows(raw), payloadRows(upload))).toEqual([{ kind: 'ok' }]);
    expect(linkStates([{ from: '$body.fileName', to: 'u-file' }], responseRows(raw), payloadRows(upload))[0].kind).toBe('red');
  });
});
