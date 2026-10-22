import type { ContractOperation, Field, IntegrationDraft, PayloadDefinition } from './model';
import { SIMPLE_TYPES } from './model';
import type { Link, Tab } from './board';
import { exampleInput, linkStates, payloadRows, requestRows, requiredUnmapped, responseRows } from './board';

/**
 * One line of the publish summary; `tab` lets "Show" open the board on the right tab, `definition` points to the
 * payload definition to fix instead.
 */
export interface Problem {
  tab: Tab;
  text: string;
  definition?: string;
}

/** GET and DELETE callers send the Input as query parameters (R1), so it can only hold simple fields. */
const QUERY_METHODS = ['GET', 'DELETE'];
const isSimple = (f: Field) =>
  f.value !== undefined || SIMPLE_TYPES.includes(f.type) || (f.type === 'list' && !!f.items && SIMPLE_TYPES.includes(f.items.type));

/**
 * The publish summary (D-6, D-7, R8): red and broken links and a nested Input for GET/DELETE block publish, amber
 * warnings (required fields not mapped) don't. Uses the same checks as the board, so both always agree.
 */
export function publishSummary(
  draft: IntegrationDraft,
  input: PayloadDefinition | null,
  output: PayloadDefinition | null,
  op: ContractOperation | null,
): { blocking: Problem[]; warnings: Problem[] } {
  const blocking: Problem[] = [];
  const warnings: Problem[] = [];
  const nested = input?.fields.filter((f) => !isSimple(f)) ?? [];
  if (op && input && QUERY_METHODS.includes(op.method) && nested.length > 0) {
    blocking.push({
      tab: 'request',
      definition: input.id,
      text: `Callers of a ${op.method} integration send the Input as query parameters, so it can only have simple fields. Change or remove in ${input.name}: ${nested.map((f) => f.name).join(', ')}`,
    });
  }
  const sides: Record<Tab, [ReturnType<typeof payloadRows>, ReturnType<typeof payloadRows>]> = {
    request: [payloadRows(input), requestRows(op, draft.addedHeaders ?? [])],
    response: [responseRows(op), payloadRows(output)],
  };
  for (const tab of ['request', 'response'] as Tab[]) {
    const links = ((tab === 'request' ? draft.request?.links : draft.response?.links) ?? []) as Link[];
    const [sources, targets] = sides[tab];
    const name = (rows: typeof sources, key: string) => rows.find((r) => r.key === key)?.rule ?? key;
    linkStates(links, sources, targets).forEach((s, i) => {
      if (s.kind !== 'ok') {
        blocking.push({ tab, text: `${name(sources, links[i].from)} → ${name(targets, links[i].to)}: ${s.reason}` });
      }
    });
    for (const r of requiredUnmapped(targets, links)) {
      warnings.push({
        tab,
        text:
          tab === 'request'
            ? `Required field ${r.rule} of the API request is not mapped; the external API will likely reject calls`
            : `Required Output field ${r.rule} is not mapped; callers won't get it`,
      });
    }
  }
  return { blocking, warnings };
}

const BODY_METHODS = ['POST', 'PUT', 'PATCH'];

/** A ready curl command for the live endpoint (D-10); GET/DELETE send the Input as query parameters (R1). */
export function curlCommand(origin: string, slug: string, method: string, input: PayloadDefinition | null): string {
  const url = `${origin}/api/v1/integrations/${slug}`;
  const example = exampleInput(input) as Record<string, unknown>;
  if (BODY_METHODS.includes(method)) {
    const body = JSON.stringify(example).replace(/'/g, "'\\''");
    return `curl -X ${method} '${url}' -H 'Content-Type: application/json' -d '${body}'`;
  }
  const query = new URLSearchParams(Object.entries(example).map(([k, v]) => [k, String(v)])).toString();
  return `curl -X ${method} '${url}${query ? `?${query}` : ''}'`;
}
