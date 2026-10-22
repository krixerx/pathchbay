import { describe, expect, it } from 'vitest';
import type { ContractOperation, IntegrationDraft, PayloadDefinition } from './model';
import { curlCommand, publishSummary } from './publish';

const input: PayloadDefinition = {
  id: 'new-book',
  name: 'New book',
  fields: [
    { id: 'nb-title', name: 'title', type: 'text', required: true },
    { id: 'nb-pages', name: 'pages', type: 'integer' },
  ],
};
const output: PayloadDefinition = {
  id: 'book-ref',
  name: 'Book ref',
  fields: [{ id: 'br-id', name: 'bookId', type: 'integer', required: true }],
};
const op: ContractOperation = {
  id: 'POST /Books',
  method: 'POST',
  path: '/Books',
  parameters: [],
  requestBody: { type: 'object', fields: [{ name: 'title', type: 'text', required: true }, { name: 'id', type: 'integer' }] },
  response: { type: 'object', fields: [{ name: 'id', type: 'integer' }] },
};

describe('publishSummary', () => {
  it('blocks on red and broken links, warns on required fields not mapped', () => {
    const draft: IntegrationDraft = {
      id: 'add-book',
      request: { links: [{ from: 'nb-title', to: 'body.id' }, { from: 'nb-gone', to: 'body.title' }] },
      response: { links: [] },
    };
    const s = publishSummary(draft, input, output, op);
    expect(s.blocking.map((p) => p.tab)).toEqual(['request', 'request']);
    expect(s.blocking[0].text).toBe("title → body.id: A text value can't go into a whole number field");
    expect(s.blocking[1].text).toBe('nb-gone → body.title: Field nb-gone no longer exists');
    expect(s.warnings).toEqual([{ tab: 'response', text: "Required Output field bookId is not mapped; callers won't get it" }]);
  });

  it('is clean when every link is valid and required fields are mapped', () => {
    const draft: IntegrationDraft = {
      id: 'add-book',
      request: { links: [{ from: 'nb-title', to: 'body.title' }] },
      response: { links: [{ from: 'id', to: 'br-id' }] },
    };
    expect(publishSummary(draft, input, output, op)).toEqual({ blocking: [], warnings: [] });
  });

  it('blocks a GET integration whose Input has nested fields, like the engine does', () => {
    const nested: PayloadDefinition = {
      ...input,
      fields: [
        ...input.fields,
        { id: 'nb-tags', name: 'tags', type: 'list', items: { type: 'text' } },
        { id: 'nb-authors', name: 'authors', type: 'list', items: { type: 'object', fields: [] } },
      ],
    };
    const get = { ...op, method: 'GET', requestBody: undefined };
    const s = publishSummary({ id: 'find-book' }, nested, output, get);
    expect(s.blocking).toEqual([
      {
        tab: 'request',
        definition: 'new-book',
        text: 'Callers of a GET integration send the Input as query parameters, so it can only have simple fields. Change or remove in New book: authors',
      },
    ]);
    expect(publishSummary({ id: 'add-book' }, nested, output, op).blocking).toEqual([]);
  });
});

describe('curlCommand', () => {
  it('sends a JSON body for POST', () => {
    expect(curlCommand('http://localhost:8080', 'add-book', 'POST', input)).toBe(
      `curl -X POST 'http://localhost:8080/api/v1/integrations/add-book' -H 'Content-Type: application/json' -d '{"title":"title","pages":1}'`,
    );
  });

  it('sends query parameters for GET', () => {
    expect(curlCommand('http://h', 'get-book', 'GET', input)).toBe(`curl -X GET 'http://h/api/v1/integrations/get-book?title=title&pages=1'`);
  });
});
