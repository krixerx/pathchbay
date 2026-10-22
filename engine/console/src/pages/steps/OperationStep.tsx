import { useEffect, useMemo, useState } from 'react';
import { Alert, Badge, Button, Code, Group, Modal, Skeleton, Stack, Table, Text, TextInput } from '@mantine/core';
import { IconAlertTriangle, IconSearch } from '@tabler/icons-react';
import { api } from '../../api';
import type { Contract, ContractOperation, IntegrationDraft } from '../../model';
import { meaning } from '../../theme';
import { filterOperations } from '../../operations';

/**
 * Step 3: which operation of the contract to call. Picking one sets the external method and path. Changing it when
 * links exist asks first (D-19).
 */
export function OperationStep(props: {
  draft: IntegrationDraft;
  contractId: string | null;
  linkCount: number;
  onChange: (d: IntegrationDraft) => void;
  onBack: () => void;
  onNext: () => void;
}) {
  const { draft, contractId } = props;
  const [contract, setContract] = useState<Contract | null>(null);
  const [query, setQuery] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [pending, setPending] = useState<ContractOperation | null>(null);
  // What "fill from the API later" created, shown once after the pick.
  const [notes, setNotes] = useState<string[]>([]);

  useEffect(() => {
    setContract(null);
    if (contractId) api.contract(contractId).then(setContract, (e: Error) => setError(e.message));
  }, [contractId]);

  const shown = useMemo(() => (contract ? filterOperations(contract.operations, query) : []), [contract, query]);

  const current = draft.contract === contractId ? draft.operationId : undefined;

  const pick = async (op: ContractOperation) => {
    setPending(null);
    setError(null);
    try {
      const { notes: created, ...next } = await api.chooseOperation(draft.id, contractId!, op.id);
      setNotes(created ?? []);
      props.onChange(next);
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const request = (op: ContractOperation) =>
    current && current !== op.id && props.linkCount > 0 ? setPending(op) : pick(op);

  if (!contractId) {
    return (
      <Stack mt="lg" align="flex-start">
        <Text>Import or choose the external system's contract first.</Text>
        <Button variant="light" onClick={props.onBack}>
          Back to import
        </Button>
      </Stack>
    );
  }
  if (!contract) return error ? <ErrorBox text={error} /> : <Skeleton height={160} mt="lg" />;
  if (contract.operations.length === 0) {
    return (
      <Stack mt="lg" align="flex-start">
        <Text>“{contract.name}” has no operations, so there is nothing to call. Check the file and import it again.</Text>
        <Button variant="light" onClick={props.onBack}>
          Back to import
        </Button>
      </Stack>
    );
  }

  return (
    <Stack gap="md" mt="lg">
      <Text>
        Operations of <b>{contract.name}</b>. Pick the one this integration calls.
      </Text>
      <TextInput
        placeholder="Search by path, method or name"
        leftSection={<IconSearch size={16} />}
        value={query}
        onChange={(e) => setQuery(e.currentTarget.value)}
        maw={420}
        aria-label="Search operations"
      />
      {error && <ErrorBox text={error} />}
      <Table highlightOnHover verticalSpacing={6}>
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={90}>Method</Table.Th>
            <Table.Th>Path</Table.Th>
            <Table.Th>Summary</Table.Th>
            <Table.Th w={120} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {shown.map((op) => {
            const selected = op.id === current;
            return (
              <Table.Tr
                key={op.id}
                bg={selected ? 'var(--mantine-color-teal-light)' : undefined}
                style={{ cursor: 'pointer' }}
                tabIndex={0}
                aria-selected={selected}
                onClick={() => request(op)}
                onKeyDown={(e) => e.key === 'Enter' && request(op)}
              >
                <Table.Td>
                  <Badge variant="outline" color="gray" ff="monospace" radius="xs">
                    {op.method}
                  </Badge>
                </Table.Td>
                <Table.Td>
                  <Text ff="monospace" size="sm">
                    {op.path}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <Text size="sm" c="dimmed">
                    {op.summary ?? (op.id.includes(' ') ? '' : op.id)}
                  </Text>
                </Table.Td>
                <Table.Td>{selected && <Badge color={meaning.accent}>Selected</Badge>}</Table.Td>
              </Table.Tr>
            );
          })}
        </Table.Tbody>
      </Table>
      {shown.length === 0 && <Text c="dimmed">No operation matches “{query}”.</Text>}
      {current && draft.target && !draft.target.baseUrl && (
        <Alert color={meaning.warning} icon={<IconAlertTriangle />} title="Base URL needed">
          This contract has no absolute server address. Set the base URL on the integration's Settings page.
        </Alert>
      )}

      {notes.length > 0 && (
        <Alert color={meaning.accent} title="Filled from the operation">
          <Stack gap={2}>
            {notes.map((n) => (
              <Text key={n} size="sm">
                {n}
              </Text>
            ))}
            <Text size="sm">You can rename, remove or add fields on the Payload definitions page at any time.</Text>
          </Stack>
        </Alert>
      )}
      {current && (
        <Group>
          <Button onClick={props.onNext}>Next: map fields</Button>
        </Group>
      )}

      <Modal opened={!!pending} onClose={() => setPending(null)} title="Change the operation?">
        <Stack>
          <Text>
            This will break {props.linkCount} link{props.linkCount === 1 ? '' : 's'}. Broken links are shown with a warning
            and can be deleted on the Map step.
          </Text>
          <Text size="sm">
            New operation: <Code>{pending?.method} {pending?.path}</Code>
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setPending(null)}>
              Cancel
            </Button>
            <Button color={meaning.broken} onClick={() => pending && pick(pending)}>
              Continue
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function ErrorBox({ text }: { text: string }) {
  return (
    <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
      {text}
    </Alert>
  );
}
