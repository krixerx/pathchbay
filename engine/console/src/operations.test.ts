import { describe, expect, it } from 'vitest';
import type { ContractOperation } from './model';
import { filterOperations } from './operations';

const ops: ContractOperation[] = [
  { id: 'GET /api/v1/Books', method: 'GET', path: '/api/v1/Books', parameters: [] },
  { id: 'getBook', method: 'GET', path: '/api/v1/Books/{id}', summary: 'One book', parameters: [] },
  { id: 'POST /api/v1/Authors', method: 'POST', path: '/api/v1/Authors', parameters: [] },
];

describe('filterOperations', () => {
  it('returns everything for an empty query', () => {
    expect(filterOperations(ops, '  ')).toHaveLength(3);
  });

  it('matches every word against method, path, summary and id', () => {
    expect(filterOperations(ops, 'get books').map((o) => o.id)).toEqual(['GET /api/v1/Books', 'getBook']);
    expect(filterOperations(ops, 'one book').map((o) => o.id)).toEqual(['getBook']);
    expect(filterOperations(ops, 'POST')).toHaveLength(1);
    expect(filterOperations(ops, 'payments')).toEqual([]);
  });
});
