import { describe, expect, it } from 'vitest';
import type { IntegrationSummary } from './model';
import { filterIntegrations, statusCounts } from './integrations';

const items: IntegrationSummary[] = [
  { id: 'books-create', name: 'books-create', input: 'new-book', output: 'book-saved', method: 'POST', status: 'live' },
  { id: 'books-get', name: 'Read one book', input: 'book-id', output: 'book-details', method: 'GET', status: 'live' },
  { id: 'broken-links', input: 'order-request', output: 'ok', method: 'POST', status: 'draft' },
];

describe('filterIntegrations', () => {
  it('matches every search word against name, id, Input, Output and method', () => {
    expect(filterIntegrations(items, 'book', 'all').map((it) => it.id)).toEqual(['books-create', 'books-get']);
    expect(filterIntegrations(items, 'books-get', 'all').map((it) => it.id)).toEqual(['books-get']);
    expect(filterIntegrations(items, 'post order', 'all').map((it) => it.id)).toEqual(['broken-links']);
    expect(filterIntegrations(items, 'READ', 'all').map((it) => it.id)).toEqual(['books-get']);
  });

  it('filters by status', () => {
    expect(filterIntegrations(items, '', 'draft').map((it) => it.id)).toEqual(['broken-links']);
    expect(filterIntegrations(items, 'book', 'draft')).toEqual([]);
    expect(filterIntegrations(items, '', 'all')).toHaveLength(3);
  });

  it('counts each status', () => {
    expect(statusCounts(items)).toEqual({ live: 2, draft: 1, broken: 0 });
  });
});
