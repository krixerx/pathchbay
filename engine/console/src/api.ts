import type {
  Auth,
  CallList,
  Connection,
  Contract,
  ContractOperation,
  Formats,
  ContractSummary,
  TestResult,
  VersionList,
  DefinitionSummary,
  IntegrationDraft,
  IntegrationSettings,
  IntegrationSummary,
  ImportReport,
  PayloadDefinition,
} from './model';

/** Error with the engine's plain-language detail, shown as is (D-6); `problems` lists each one when there are several. */
export class ApiError extends Error {
  problems: string[];

  constructor(message: string, problems: string[] = []) {
    super(message);
    this.problems = problems;
  }
}

/**
 * The engine's INBOUND_API_KEY, typed once per browser tab when the engine asks for it (401). Kept in
 * sessionStorage, so it is gone when the tab closes; storage may be blocked, then it lives until a reload.
 */
const KEY_STORAGE = 'mapper.inboundKey';
let inboundKey: string | null = null;
try {
  inboundKey = sessionStorage.getItem(KEY_STORAGE);
} catch {
  // storage blocked: ask again after a reload
}

export function setInboundKey(key: string) {
  inboundKey = key;
  try {
    sessionStorage.setItem(KEY_STORAGE, key);
  } catch {
    // storage blocked: the key lives until a reload
  }
}

/** Fired when the engine answers 401; KeyPrompt listens for it. */
export const UNAUTHORIZED_EVENT = 'mapper:unauthorized';

async function request(method: string, path: string, body?: BodyInit, contentType?: string): Promise<Response> {
  const headers: Record<string, string> = {};
  if (contentType) headers['Content-Type'] = contentType;
  if (inboundKey) headers['X-API-Key'] = inboundKey;
  const res = await fetch(`/admin/api${path}`, { method, headers, body });
  if (res.status === 401) window.dispatchEvent(new Event(UNAUTHORIZED_EVENT));
  if (!res.ok) {
    const text = await res.text();
    let data: { detail?: string; problems?: string[] } | null = null;
    try {
      data = text ? JSON.parse(text) : null;
    } catch {
      // not the engine's JSON error, e.g. a proxy page: the status says enough
    }
    throw new ApiError(data?.detail ?? `${res.status} ${res.statusText}`, data?.problems ?? []);
  }
  return res;
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const json = body === undefined ? undefined : JSON.stringify(body);
  const res = await request(method, path, json, json === undefined ? undefined : 'application/json');
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
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
  deleteIntegration: (slug: string) => call<null>('DELETE', `/integrations/${slug}`),
  createIntegration: (name: string) => call<IntegrationDraft>('POST', '/integrations', { name }),
  setPayloads: (slug: string, input: string | null, output: string | null) =>
    call<IntegrationDraft>('PUT', `/integrations/${slug}/payloads`, { input, output }),

  contracts: () => call<ContractSummary[]>('GET', '/contracts'),
  contract: (id: string) => call<Contract>('GET', `/contracts/${id}`),
  deleteContract: (id: string) => call<null>('DELETE', `/contracts/${id}`),
  updateContract: (id: string, name: string, source: { url?: string; content?: string }) =>
    call<Contract & { notes?: string[] }>('PUT', `/contracts/${id}`, { name, ...source }),
  importContract: (name: string, source: { url?: string; content?: string }) =>
    call<Contract>('POST', '/contracts', { name, ...source }),
  chooseOperation: (slug: string, contract: string, operationId: string) =>
    call<IntegrationDraft>('PUT', `/integrations/${slug}/operation`, { contract, operationId }),
  board: (slug: string) =>
    call<{ draft: IntegrationDraft; input: PayloadDefinition | null; output: PayloadDefinition | null; operation: ContractOperation | null }>(
      'GET',
      `/integrations/${slug}/board`,
    ),
  saveLinks: (slug: string, request: { from: string; to: string }[], response: { from: string; to: string }[]) =>
    call<IntegrationDraft>('PUT', `/integrations/${slug}/links`, { request, response }),
  saveHeaders: (slug: string, headers: string[]) => call<IntegrationDraft>('PUT', `/integrations/${slug}/headers`, { headers }),
  saveFormats: (slug: string, formats: Formats) => call<IntegrationDraft>('PUT', `/integrations/${slug}/formats`, formats),
  test: (slug: string, input: unknown, real: boolean) => call<TestResult>('POST', `/integrations/${slug}/test`, { input, real }),
  versions: (slug: string) => call<VersionList>('GET', `/integrations/${slug}/versions`),
  calls: (slug: string) => call<CallList>('GET', `/integrations/${slug}/calls`),
  publish: (slug: string) => call<VersionList>('POST', `/integrations/${slug}/publish`),
  activate: (slug: string, version: string) => call<VersionList>('POST', `/integrations/${slug}/versions/${version}/activate`),
  settings: (slug: string) => call<IntegrationSettings>('GET', `/integrations/${slug}/settings`),
  connection: (id: string) => call<Connection>('GET', `/connections/${id}`),
  saveConnection: (id: string, baseUrl: string, headers: { name: string; value: string; secret: boolean }[]) =>
    call<Connection>('PUT', `/connections/${id}`, { baseUrl, headers }),
  /** The picked integrations as a package (D-28), with the file name the engine suggests. */
  exportPackage: async (slugs: string[]) => {
    const res = await request('POST', '/export', JSON.stringify({ integrations: slugs }), 'application/json');
    const name = /filename="([^"]+)"/.exec(res.headers.get('Content-Disposition') ?? '')?.[1] ?? 'patchbay.zip';
    return { name, blob: await res.blob() };
  },
  importPackage: async (file: File) => (await (await request('POST', '/import', file, 'application/zip')).json()) as ImportReport,
  saveSettings: (slug: string, update: { baseUrl?: string; auth: Auth | null; secret?: string; removeSecret?: boolean }) =>
    call<IntegrationSettings>('PUT', `/integrations/${slug}/settings`, update),
};
