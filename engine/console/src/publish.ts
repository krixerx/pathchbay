import type { ContractOperation, IntegrationDraft, PayloadDefinition } from './model';
import type { Link, Tab } from './board';
import { exampleInput, linkStates, payloadRows, requestRows, requiredUnmapped, responseRows } from './board';

/** One line of the step 5 summary; `tab` lets "Show" open the board on the right tab. */
export interface Problem {
  tab: Tab;
  text: string;
}

/**
 * The step 5 summary (D-6, D-7, R8): red and broken links block publish, amber warnings (required fields not
 * mapped) don't. Uses the same checks as the board, so both always agree.
 */
export function publishSummary(
  draft: IntegrationDraft,
  input: PayloadDefinition | null,
  output: PayloadDefinition | null,
  op: ContractOperation | null,
): { blocking: Problem[]; warnings: Problem[] } {
  const blocking: Problem[] = [];
  const warnings: Problem[] = [];
  const sides: Record<Tab, [ReturnType<typeof payloadRows>, ReturnType<typeof payloadRows>]> = {
    request: [payloadRows(input), requestRows(op)],
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
