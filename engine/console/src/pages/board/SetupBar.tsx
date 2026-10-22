import { useEffect, useState } from 'react';
import type { ComboboxItem, OptionsFilter } from '@mantine/core';
import { Alert, Anchor, Button, Code, Group, Modal, Paper, Select, Stack, Text } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { Link } from 'react-router-dom';
import { api } from '../../api';
import type { Contract, ContractSummary, DefinitionSummary, IntegrationDraft } from '../../model';
import { meaning } from '../../theme';

/** Every search word must appear in the option's text (method, path, summary). */
const wordFilter: OptionsFilter = ({ options, search }) => {
  const words = search.toLowerCase().split(/\s+/).filter(Boolean);
  return (options as ComboboxItem[]).filter((o) => words.every((w) => o.label.toLowerCase().includes(w)));
};

/**
 * The top of the board: what callers send (Input) and get back (Output), and which operation of which Swagger
 * configuration is called. All saved at once (D-1). An Input or Output left empty is created from the operation
 * when it is picked, already linked ("fill from the API").
 */
export function SetupBar(props: { draft: IntegrationDraft; linkCount: number; onChange: (d: IntegrationDraft) => void }) {
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

  return (
    <Paper withBorder p="sm">
      <Stack gap="xs">
        <Group align="flex-start" grow wrap="nowrap">
          <Select
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
            label="Output"
            description="What callers get back"
            placeholder="Empty: created from the operation"
            data={definitionOptions}
            value={draft.output ?? null}
            onChange={(v) => choosePayload('output', v)}
            searchable
            clearable
          />
          <Select
            label="Swagger configuration"
            description={
              <>
                External system,{' '}
                <Anchor component={Link} to="/configuration" size="xs">
                  import under Configuration
                </Anchor>
              </>
            }
            placeholder={contracts?.length === 0 ? 'Nothing imported yet' : 'Choose'}
            data={(contracts ?? []).map((c) => ({ value: c.id, label: `${c.name} · ${c.operationCount} operations` }))}
            value={contractId}
            onChange={setContractId}
            searchable
          />
          <Select
            label="Operation"
            description="What this integration calls"
            placeholder={contractId ? 'Search method, path or name' : 'Choose a configuration first'}
            disabled={!contract}
            data={(contract?.operations ?? []).map((o) => ({
              value: o.id,
              label: `${o.method} ${o.path}${o.summary ? ` · ${o.summary}` : ''}`,
            }))}
            value={currentOp}
            onChange={requestOperation}
            filter={wordFilter}
            searchable
            maxDropdownHeight={360}
          />
        </Group>

        {draft.operationId && draft.target && !draft.target.baseUrl && (
          <Alert color={meaning.warning} icon={<IconAlertTriangle />} p="xs">
            This configuration has no absolute server address. Set the base URL on the{' '}
            <Anchor component={Link} to={`/integrations/${draft.id}/settings`}>
              Settings
            </Anchor>{' '}
            page.
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
              {pendingOp?.method} {pendingOp?.path}
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
