import type { ContractOperation } from './model';

/** Search over method, path, summary and id; every word must match (case-insensitive). */
export function filterOperations(operations: ContractOperation[], query: string): ContractOperation[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  if (words.length === 0) return operations;
  return operations.filter((op) => {
    const text = `${op.method} ${op.path} ${op.summary ?? ''} ${op.id}`.toLowerCase();
    return words.every((w) => text.includes(w));
  });
}
