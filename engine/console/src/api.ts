import type { DefinitionSummary, IntegrationDraft, IntegrationSummary, PayloadDefinition } from './model';

/** Error with the engine's plain-language detail, shown as is (D-6). */
export class ApiError extends Error {}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`/admin/api${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) {
    throw new ApiError(data?.detail ?? `${res.status} ${res.statusText}`);
  }
  return data as T;
}

export const api = {
  definitions: () => call<DefinitionSummary[]>('GET', '/definitions'),
  definition: (id: string) => call<{ definition: PayloadDefinition; usedBy: string[] }>('GET', `/definitions/${id}`),
  saveDefinition: (def: PayloadDefinition) =>
    call<{ definition: PayloadDefinition; usedBy: string[] }>('PUT', `/definitions/${def.id}`, def),
  infer: (id: string, name: string, sample: unknown) =>
    call<{ definition: PayloadDefinition; guesses: string[] }>('POST', '/definitions/infer', { id, name, sample }),

  integrations: () => call<IntegrationSummary[]>('GET', '/integrations'),
  integration: (slug: string) => call<IntegrationDraft>('GET', `/integrations/${slug}`),
  createIntegration: (name: string) => call<IntegrationDraft>('POST', '/integrations', { name }),
  setPayloads: (slug: string, input: string | null, output: string | null) =>
    call<IntegrationDraft>('PUT', `/integrations/${slug}/payloads`, { input, output }),
};
