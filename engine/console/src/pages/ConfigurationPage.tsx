import { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Code,
  FileInput,
  Group,
  List,
  Modal,
  Paper,
  Skeleton,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconFileUpload, IconInfoCircle, IconLink, IconPencil, IconPlugConnected, IconTrash } from '@tabler/icons-react';
import { api } from '../api';
import { ConnectionForm } from '../components/ConnectionForm';
import type { Contract, ContractSummary } from '../model';
import { meaning } from '../theme';

/**
 * Configuration: the external systems' Swagger/OpenAPI files (D-5). Imported once here, used by any number of
 * integrations, which pick one of its operations on their board. Each has a connection with its base URL in this
 * environment (D-26).
 */
export function ConfigurationPage() {
  const [contracts, setContracts] = useState<ContractSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<Contract | null>(null);
  const [deleting, setDeleting] = useState<ContractSummary | null>(null);
  const [editing, setEditing] = useState<ContractSummary | null>(null);
  const [connecting, setConnecting] = useState<ContractSummary | null>(null);

  const load = () => api.contracts().then(setContracts, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
  }, []);

  const remove = async (c: ContractSummary) => {
    setDeleting(null);
    setError(null);
    try {
      await api.deleteContract(c.id);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  return (
    <Stack gap="lg" maw={1100}>
      <div>
        <Title order={2}>Configuration</Title>
        <Text c="dimmed" size="sm">
          Swagger and OpenAPI files of the external systems. Import one once; every integration can call its operations.
        </Text>
      </div>

      <ImportForm onImported={load} />

      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
          {error}
        </Alert>
      )}
      {contracts === null && !error && <Skeleton height={100} />}
      {contracts?.length === 0 && <Text c="dimmed">Nothing imported yet.</Text>}
      {contracts && contracts.length > 0 && (
        <Table verticalSpacing="sm" highlightOnHover>
          <Table.Thead>
            <Table.Tr>
              <Table.Th>External system</Table.Th>
              <Table.Th>Operations</Table.Th>
              <Table.Th>Base URL</Table.Th>
              <Table.Th>Used by</Table.Th>
              <Table.Th />
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {contracts.map((c) => (
              <Table.Tr key={c.id}>
                <Table.Td>
                  <Text fw={500}>{c.name}</Text>
                  <Text size="xs" c="dimmed">
                    <Code>{c.id}</Code> · imported {new Date(c.importedAt).toLocaleString()}
                    {c.warningCount > 0 && ` · ${c.warningCount} warning${c.warningCount === 1 ? '' : 's'}`}
                  </Text>
                </Table.Td>
                <Table.Td>{c.operationCount}</Table.Td>
                <Table.Td>
                  {c.baseUrl ? (
                    <Text size="sm" ff="monospace">
                      {c.baseUrl}
                      {c.baseUrlInEnv && (
                        <Badge ml={6} size="xs" variant="light" color="gray">
                          env
                        </Badge>
                      )}
                    </Text>
                  ) : (
                    <Text size="sm" c="yellow.8">
                      Not set
                    </Text>
                  )}
                </Table.Td>
                <Table.Td>
                  <Text size="sm">{c.usedBy.length ? c.usedBy.join(', ') : '–'}</Text>
                </Table.Td>
                <Table.Td>
                  <Group gap="xs" justify="flex-end" wrap="nowrap">
                    <Button size="compact-sm" variant="default" onClick={() => api.contract(c.id).then(setOpen, (e: Error) => setError(e.message))}>
                      Operations
                    </Button>
                    <Button size="compact-sm" variant="default" leftSection={<IconPlugConnected size={14} />} onClick={() => setConnecting(c)}>
                      Connection
                    </Button>
                    <Button size="compact-sm" variant="default" leftSection={<IconPencil size={14} />} onClick={() => setEditing(c)}>
                      Edit
                    </Button>
                    <Tooltip label={`Used by ${c.usedBy.join(', ')}`} disabled={c.usedBy.length === 0} withArrow>
                      <Button
                        size="compact-sm"
                        variant="subtle"
                        color={meaning.broken}
                        leftSection={<IconTrash size={14} />}
                        disabled={c.usedBy.length > 0}
                        onClick={() => setDeleting(c)}
                      >
                        Delete
                      </Button>
                    </Tooltip>
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <Modal opened={!!open} onClose={() => setOpen(null)} title={open?.name} size="lg">
        {open && (
          <Stack gap="xs">
            {open.warnings.length > 0 && (
              <Alert color={meaning.warning} icon={<IconInfoCircle />} title="Imported with warnings">
                <List size="sm">
                  {open.warnings.map((w) => (
                    <List.Item key={w}>{w}</List.Item>
                  ))}
                </List>
              </Alert>
            )}
            {open.operations.map((op) => (
              <Group key={op.id} gap="xs" wrap="nowrap">
                <Badge variant="outline" color="gray" ff="monospace" radius="xs" w={70}>
                  {op.method}
                </Badge>
                <Text size="sm" ff="monospace">
                  {op.path}
                </Text>
                {op.summary && (
                  <Text size="sm" c="dimmed" truncate>
                    {op.summary}
                  </Text>
                )}
              </Group>
            ))}
          </Stack>
        )}
      </Modal>

      <Modal opened={!!connecting} onClose={() => setConnecting(null)} title={`Connection of “${connecting?.name}”`} size="lg">
        {connecting && <ConnectionForm key={connecting.id} id={connecting.id} onSaved={load} />}
      </Modal>

      <Modal opened={!!editing} onClose={() => setEditing(null)} title={`Edit “${editing?.name}”`} size="lg">
        {editing && <ImportForm key={editing.id} existing={editing} onImported={load} />}
      </Modal>

      <Modal opened={!!deleting} onClose={() => setDeleting(null)} title="Delete this configuration?">
        <Stack>
          <Text>
            “{deleting?.name}” and its original file are deleted. No integration uses it, so nothing live changes.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setDeleting(null)}>
              Cancel
            </Button>
            <Button color={meaning.broken} onClick={() => deleting && remove(deleting)}>
              Delete
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

/**
 * Upload a file or give a URL (fetched by the engine). Unresolved parts become warnings; the import still goes on.
 * With `existing` it edits that configuration: the name can change, a new file is optional, the id stays.
 */
function ImportForm(props: { existing?: ContractSummary; onImported: () => void }) {
  const { existing } = props;
  const [name, setName] = useState(existing?.name ?? '');
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState('');
  const [mode, setMode] = useState<string | null>('file');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<(Contract & { notes?: string[] }) | null>(null);

  const importNow = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const source = mode === 'file' ? (file ? { content: await file.text() } : {}) : url.trim() ? { url } : {};
      setResult(existing ? await api.updateContract(existing.id, name, source) : await api.importContract(name, source));
      if (!existing) setName('');
      setFile(null);
      setUrl('');
      props.onImported();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const ready = name.trim() && (existing || (mode === 'file' ? !!file : url.trim().length > 0));

  return (
    <Paper withBorder={!existing} p={existing ? 0 : 'md'} maw={720}>
      <Stack gap="sm">
        {!existing && <Text fw={600}>Import a Swagger or OpenAPI file</Text>}
        <TextInput
          label="Name of the external system"
          description={
            existing
              ? 'Only the shown name changes; integrations using it stay connected.'
              : 'For example “Population register”. Importing again under the same name replaces it.'
          }
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
        />
        {existing && (
          <Text size="sm" c="dimmed">
            Replace the file (optional): upload a new version or give its URL. Leave both empty to keep the current file.
          </Text>
        )}
        <Tabs value={mode} onChange={setMode}>
          <Tabs.List>
            <Tabs.Tab value="file" leftSection={<IconFileUpload size={16} />}>
              Upload a file
            </Tabs.Tab>
            <Tabs.Tab value="url" leftSection={<IconLink size={16} />}>
              From a URL
            </Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="file" pt="sm">
            <FileInput
              label="OpenAPI or Swagger file"
              description="JSON or YAML. Swagger 2.0 is converted."
              placeholder="Choose a file"
              accept=".json,.yaml,.yml,application/json"
              value={file}
              onChange={setFile}
              clearable
            />
          </Tabs.Panel>
          <Tabs.Panel value="url" pt="sm">
            <TextInput
              label="URL of the OpenAPI or Swagger file"
              description="The engine fetches it, so it must be reachable from where the engine runs."
              placeholder="https://example.org/swagger/v1/swagger.json"
              value={url}
              onChange={(e) => setUrl(e.currentTarget.value)}
            />
          </Tabs.Panel>
        </Tabs>
        <Group>
          <Button onClick={importNow} loading={busy} disabled={!ready}>
            {existing ? 'Save' : 'Import'}
          </Button>
        </Group>
        {error && (
          <Alert color={meaning.broken} icon={<IconAlertTriangle />} title="Not imported" role="alert">
            {error}
          </Alert>
        )}
        {result && (
          <Alert
            color={result.notes?.length ? meaning.warning : meaning.accent}
            icon={<IconCheck />}
            title={`“${result.name}” ${existing ? 'saved' : 'imported'}: ${result.operations.length} operations`}
          >
            {result.notes?.length ? (
              <List size="sm">
                {result.notes.map((n) => (
                  <List.Item key={n}>{n}</List.Item>
                ))}
              </List>
            ) : result.warnings.length > 0 ? (
              <List size="sm">
                {result.warnings.map((w) => (
                  <List.Item key={w}>{w}</List.Item>
                ))}
              </List>
            ) : (
              'Integrations can now pick its operations.'
            )}
          </Alert>
        )}
      </Stack>
    </Paper>
  );
}
