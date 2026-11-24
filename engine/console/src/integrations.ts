import type { IntegrationSummary } from './model';

export type StatusFilter = 'all' | IntegrationSummary['status'];

/**
 * The integrations shown on Home: every search word must appear in the name, id, Input, Output or method, and the
 * status must match unless the filter is 'all'.
 */
export function filterIntegrations(items: IntegrationSummary[], query: string, status: StatusFilter): IntegrationSummary[] {
  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  return items.filter((it) => {
    if (status !== 'all' && it.status !== status) return false;
    const text = [it.name, it.id, it.input, it.output, it.method].filter(Boolean).join(' ').toLowerCase();
    return words.every((w) => text.includes(w));
  });
}

/** How many integrations have each status, for the filter's labels. */
export function statusCounts(items: IntegrationSummary[]): Record<IntegrationSummary['status'], number> {
  const counts = { live: 0, draft: 0, broken: 0 };
  for (const it of items) counts[it.status]++;
  return counts;
}
