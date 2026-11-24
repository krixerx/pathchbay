import { useEffect, useState } from 'react';
import type { ComboboxItem, OptionsFilter } from '@mantine/core';
import { Alert, Anchor, Button, Code, Group, Modal, Paper, Select, Stack, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { Link } from 'react-router-dom';
import { api } from '../../api';
import type { Contract, ContractSummary, DefinitionSummary, IntegrationDraft } from '../../model';
import { meaning } from '../../theme';

/** Every search word must appear in the option's text: its label and, when given, its summary. */
const wordFilter =
  (summaries: Map<string, string>): OptionsFilter =>
  ({ options, search }) => {
    const words = search.toLowerCase().split(/\s+/).filter(Boolean);
    return (options as ComboboxItem[]).filter((o) => {
      const text = `${o.label} ${summaries.get(o.value) ?? ''}`.toLowerCase();
      return words.every((w) => text.includes(w));
    });
  };

/**
 * The top of the board: what callers send (Input) and get back (Output), and which operation of which Swagger
 * configuration is called. All saved at once (D-1). An Input or Output left empty is created from the operation
 * when it is picked, already linked ("fill from the API").
 */
export function SetupBar(props: { draft: IntegrationDraft; baseUrl: string | null; linkCount: number; onChange: (d: IntegrationDraft) => void }) {
  const { draft } = props;
  const [definitions, setDefinitions] = useState<DefinitionSummary[]>([]);
  const [contracts, setContracts] = useState<ContractSummary[] | null>(null);
  const [contractId, setContractId] = useState<string | null>(draft.contract ?? null);
  const [contract, setContract] = useState<Contract | null>(null);
  const [pending, setPending] = useState<string | null>(null);
  const [notes, setNotes] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.definitions().then(setDefinitions, (e: Error) => setError(e.message));
    api.contracts().then(setContracts, (e: Error) => setError(e.message));
  }, [draft.input, draft.output]);

  useEffect(() => {
    setContract(null);
    if (contractId) api.contract(contractId).then(setContract, (e: Error) => setError(e.message));
  }, [contractId]);

  const run = async (action: () => Promise<IntegrationDraft>) => {
    setError(null);
    try {
      const { notes: created, ...next } = await action();
      setNotes(created ?? []);
      props.onChange(next);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const choosePayload = (part: 'input' | 'output', value: string | null) => {
    const next = { ...draft, [part]: value ?? undefined };
    run(() => api.setPayloads(draft.id, next.input ?? null, next.output ?? null));
  };

  const chooseOperation = (opId: string) => {
    setPending(null);
    run(() => api.chooseOperation(draft.id, contractId!, opId));
  };

  const currentOp = draft.contract === contractId ? (draft.operationId ?? null) : null;
  const requestOperation = (opId: string | null) => {
    if (!opId || opId === currentOp) return;
    if (draft.operationId && props.linkCount > 0) setPending(opId);
    else chooseOperation(opId);
  };

  const definitionOptions = definitions.map((d) => ({ value: d.id, label: `${d.name ?? d.id} · ${d.fieldCount} fields` }));
  const pendingOp = contract?.operations.find((o) => o.id === pending);
  // The operation's summary is shown under the select and in the list, not in the select itself, where it was cut off.
  const summaries = new Map((contract?.operations ?? []).filter((o) => o.summary).map((o) => [o.id, o.summary!]));
  const currentSummary = currentOp ? summaries.get(currentOp) : undefined;

  return (
    <Paper withBorder p="sm">
      <Stack gap="xs">
        {/* In the order the work goes: the system and its operation first, then Input and Output, which are created
            from the operation when left empty. */}
        <Group align="flex-start" wrap="nowrap">
          <Select
            style={{ flex: 1, minWidth: 0 }}
            label="External system"
            description={
              <Anchor component={Link} to="/configuration" size="xs">
                Imported under Configuration
              </Anchor>
            }
            placeholder={contracts?.length === 0 ? 'Nothing imported yet' : 'Choose'}
            data={(contracts ?? []).map((c) => ({ value: c.id, label: `${c.name} · ${c.operationCount} operations` }))}
            value={contractId}
            onChange={setContractId}
            searchable
          />
          <Stack gap={4} style={{ flex: 2, minWidth: 0 }}>
            <Select
              label="Operation"
              description="What this integration calls"
              placeholder={contractId ? 'Search method, path or name' : 'Choose an external system first'}
              disabled={!contract}
              data={(contract?.operations ?? []).map((o) => ({
                value: o.id,
                label: `${operationLabel(o)}${o.unsupported ? ` (not supported: ${o.unsupported})` : ''}`,
                disabled: !!o.unsupported,
              }))}
              value={currentOp}
              onChange={requestOperation}
              filter={wordFilter(summaries)}
              renderOption={({ option }) => (
                <div>
                  <Text size="sm">{option.label}</Text>
                  {summaries.has(option.value) && (
                    <Text size="xs" c="dimmed">
                      {summaries.get(option.value)}
                    </Text>
                  )}
                </div>
              )}
              searchable
              maxDropdownHeight={360}
            />
            {currentSummary && (
              <Text size="xs" c="dimmed" lineClamp={2} title={currentSummary}>
                {currentSummary}
              </Text>
            )}
          </Stack>
          <Select
            style={{ flex: 1, minWidth: 0 }}
            label="Input"
            description="What callers send"
            placeholder="Empty: created from the operation"
            data={definitionOptions}
            value={draft.input ?? null}
            onChange={(v) => choosePayload('input', v)}
            searchable
            clearable
          />
          <Select
            style={{ flex: 1, minWidth: 0 }}
            label="Output"
            description="What callers get back"
            placeholder="Empty: created from the operation"
            data={definitionOptions}
            value={draft.output ?? null}
            onChange={(v) => choosePayload('output', v)}
            searchable
            clearable
          />
        </Group>

        {draft.operationId && draft.target && !props.baseUrl && (
          <Alert color={meaning.warning} icon={<IconAlertTriangle />} p="xs">
            This Swagger configuration has no base URL in this environment yet. Set it with <b>Connection</b> under{' '}
            <Anchor component={Link} to="/configuration">
              Configuration
            </Anchor>
            .
          </Alert>
        )}
        {notes.length > 0 && (
          <Alert color={meaning.accent} title="Filled from the operation" p="xs" withCloseButton onClose={() => setNotes([])}>
            {notes.map((n) => (
              <Text key={n} size="sm">
                {n}
              </Text>
            ))}
          </Alert>
        )}
        {error && (
          <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert" p="xs">
            {error}
          </Alert>
        )}
      </Stack>

      <Modal opened={!!pending} onClose={() => setPending(null)} title="Change the operation?">
        <Stack>
          <Text>
            This will break {props.linkCount} link{props.linkCount === 1 ? '' : 's'}. Broken links are shown in red and can be
            deleted on the board.
          </Text>
          <Text size="sm">
            New operation:{' '}
            <Code>
              {pendingOp && operationLabel(pendingOp)}
            </Code>
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setPending(null)}>
              Cancel
            </Button>
            <Button color={meaning.broken} onClick={() => pending && chooseOperation(pending)}>
              Change
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Paper>
  );
}

/** How an operation is named in the list: SOAP and its port/operation for a WSDL (D-30), else method and path. */
export function operationLabel(o: { method: string; path: string; id: string; protocol?: string; soap?: { version: string } }) {
  return o.protocol === 'soap' ? `SOAP ${o.soap?.version ?? ''} ${o.id}`.replace('  ', ' ') : `${o.method} ${o.path}`;
}
