import { useEffect, useState } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Code,
  FileInput,
  Group,
  List,
  Modal,
  Paper,
  Popover,
  Skeleton,
  Stack,
  Table,
  Tabs,
  Text,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core';
import {
  IconAlertTriangle,
  IconCheck,
  IconFileUpload,
  IconInfoCircle,
  IconLink,
  IconPencil,
  IconPlugConnected,
  IconPlus,
  IconTrash,
} from '@tabler/icons-react';
import { Link } from 'react-router-dom';
import { api } from '../api';
import { ConnectionForm } from '../components/ConnectionForm';
import type { Contract, ContractSummary } from '../model';
import { meaning } from '../theme';
import { shortWhen } from '../time';

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
  const [importing, setImporting] = useState(false);

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
      <Group justify="space-between" align="flex-start">
        <div>
          <Title order={2}>Configuration</Title>
          <Text c="dimmed" size="sm">
            Swagger, OpenAPI and WSDL files of the external systems. Import one once; every integration can call its operations.
          </Text>
        </div>
        {contracts && contracts.length > 0 && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setImporting(true)}>
            Import external system
          </Button>
        )}
      </Group>

      {/* Before the first import the form is the page's one task, so it stays on the page; after that the list is. */}
      {contracts?.length === 0 && <ImportForm onImported={load} />}

      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
          {error}
        </Alert>
      )}
      {contracts === null && !error && <Skeleton height={100} />}
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
                    {c.name !== c.id && (
                      <>
                        <Code>{c.id}</Code> ·{' '}
                      </>
                    )}
                    <span title={new Date(c.importedAt).toLocaleString()}>imported {shortWhen(new Date(c.importedAt))}</span>
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
                  <UsedBy ids={c.usedBy} />
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
                    {/* In use, Delete is inactive but stays focusable (data-disabled, not disabled), so keyboard users
                        get the reason too. */}
                    <Tooltip
                      label={`Used by ${c.usedBy.length} integration${c.usedBy.length === 1 ? '' : 's'}. Delete them or move them to another system first.`}
                      disabled={c.usedBy.length === 0}
                      withArrow
                      multiline
                      w={260}
                      events={{ hover: true, focus: true, touch: true }}
                    >
                      <Button
                        size="compact-sm"
                        variant="subtle"
                        color={meaning.broken}
                        leftSection={<IconTrash size={14} />}
                        data-disabled={c.usedBy.length > 0 || undefined}
                        aria-disabled={c.usedBy.length > 0}
                        onClick={(e) => (c.usedBy.length > 0 ? e.preventDefault() : setDeleting(c))}
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

      <Modal opened={importing} onClose={() => setImporting(false)} title="Import a Swagger, OpenAPI or WSDL file" size="lg">
        <ImportForm bare onImported={load} />
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
function ImportForm(props: { existing?: ContractSummary; onImported: () => void; bare?: boolean }) {
  const { existing } = props;
  // In a dialog the form has no frame and no title of its own: the dialog gives both.
  const bare = props.bare || !!existing;
  const [name, setName] = useState(existing?.name ?? '');
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState(existing?.sourceUrl ?? '');
  const [mode, setMode] = useState<string | null>(existing?.sourceUrl ? 'url' : 'file');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<(Contract & { notes?: string[] }) | null>(null);

  const importNow = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      // A ZIP (a WSDL with its XSD files, D-30) goes as base64; any other file as text.
      const source =
        mode === 'file'
          ? file
            ? file.name.toLowerCase().endsWith('.zip')
              ? { zip: await toBase64(file) }
              : { content: await file.text() }
            : {}
          : url.trim()
            ? { url }
            : {};
      const saved = existing ? await api.updateContract(existing.id, name, source) : await api.importContract(name, source);
      setResult(saved);
      if (!existing) setName('');
      setFile(null);
      setUrl(existing ? (saved.sourceUrl ?? '') : '');
      props.onImported();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const ready = name.trim() && (existing || (mode === 'file' ? !!file : url.trim().length > 0));

  return (
    <Paper withBorder={!bare} p={bare ? 0 : 'md'} maw={720}>
      <Stack gap="sm">
        {!bare && <Text fw={600}>Import a Swagger, OpenAPI or WSDL file</Text>}
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
            {existing.sourceUrl
              ? 'Saving reads the file from the URL again, so it picks up changes. Upload a file instead, or clear the URL to keep the current file.'
              : 'Replace the file (optional): upload a new version or give its URL. Leave both empty to keep the current file.'}
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
              label="OpenAPI, Swagger or WSDL file"
              description="JSON or YAML (Swagger 2.0 is converted), a WSDL 1.1 file, or a ZIP with a WSDL and its XSD files."
              placeholder="Choose a file"
              accept=".json,.yaml,.yml,.wsdl,.xml,.zip,application/json"
              value={file}
              onChange={setFile}
              clearable
            />
          </Tabs.Panel>
          <Tabs.Panel value="url" pt="sm">
            <TextInput
              label="URL of the OpenAPI, Swagger or WSDL file"
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

/** "4 integrations", opening the list with a link to each; a single one is linked directly. */
function UsedBy({ ids }: { ids: string[] }) {
  if (ids.length === 0) return <Text size="sm">–</Text>;
  if (ids.length === 1) {
    return (
      <Anchor size="sm" component={Link} to={`/integrations/${ids[0]}`}>
        {ids[0]}
      </Anchor>
    );
  }
  return (
    <Popover position="bottom-start" withArrow shadow="sm" trapFocus returnFocus>
      <Popover.Target>
        <Anchor size="sm" component="button" type="button">
          {ids.length} integrations
        </Anchor>
      </Popover.Target>
      <Popover.Dropdown>
        <Stack gap={4}>
          {ids.map((id) => (
            <Anchor key={id} size="sm" component={Link} to={`/integrations/${id}`}>
              {id}
            </Anchor>
          ))}
        </Stack>
      </Popover.Dropdown>
    </Popover>
  );
}

/** A file's bytes as base64, for a ZIP upload. */
async function toBase64(file: File): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(i, i + 0x8000));
  }
  return btoa(binary);
}
