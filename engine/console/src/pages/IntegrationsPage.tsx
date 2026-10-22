import { useEffect, useState } from 'react';
import {
  Alert,
  Anchor,
  Badge,
  Button,
  Checkbox,
  Code,
  FileButton,
  Group,
  List,
  Modal,
  Skeleton,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconAlertTriangle, IconApi, IconDownload, IconPlus, IconTrash, IconUpload } from '@tabler/icons-react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api';
import { ImportResult } from '../components/ImportResult';
import type { ImportReport, IntegrationSummary } from '../model';
import { slugify } from '../tree';
import { meaning } from '../theme';

const STATUS: Record<IntegrationSummary['status'], { color: string; label: string }> = {
  live: { color: meaning.accent, label: 'Live' },
  draft: { color: meaning.neutral, label: 'Draft' },
  broken: { color: meaning.broken, label: 'Broken' },
};

/**
 * Home (D-1): every integration with its status; "New integration" is the one primary action. Ticked integrations
 * can be exported as a package for another environment, and a package imported here (D-28).
 */
export function IntegrationsPage() {
  const [items, setItems] = useState<IntegrationSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [deleting, setDeleting] = useState<IntegrationSummary | null>(null);
  const [picked, setPicked] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState<'export' | 'import' | null>(null);
  const [imported, setImported] = useState<ImportReport | null>(null);
  const [refused, setRefused] = useState<ApiError | null>(null);

  const load = () =>
    api.integrations().then((list) => {
      setItems(list);
      setPicked((old) => new Set([...old].filter((id) => list.some((it) => it.id === id))));
    }, (e: Error) => setError(e.message));
  useEffect(() => {
    load();
  }, []);

  const remove = async (it: IntegrationSummary) => {
    setDeleting(null);
    setError(null);
    try {
      await api.deleteIntegration(it.id);
      load();
    } catch (e) {
      setError((e as Error).message);
    }
  };

  const exportPicked = async () => {
    setBusy('export');
    setError(null);
    try {
      const { name, blob } = await api.exportPackage([...picked]);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = name;
      a.click();
      URL.revokeObjectURL(url);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(null);
    }
  };

  const importFile = async (file: File | null) => {
    if (!file) return;
    setBusy('import');
    setError(null);
    try {
      setImported(await api.importPackage(file));
      load();
    } catch (e) {
      setRefused(e instanceof ApiError ? e : new ApiError((e as Error).message));
    } finally {
      setBusy(null);
    }
  };

  return (
    <Stack gap="lg" maw={1100}>
      <Group justify="space-between">
        <Title order={2}>Integrations</Title>
        <Group gap="xs">
          <Button
            variant="default"
            leftSection={<IconDownload size={16} />}
            disabled={picked.size === 0}
            loading={busy === 'export'}
            onClick={exportPicked}
            title={picked.size === 0 ? 'Tick the integrations to export first' : undefined}
          >
            Export{picked.size > 0 ? ` ${picked.size}` : ''}
          </Button>
          <FileButton onChange={importFile} accept=".zip,application/zip">
            {(p) => (
              <Button {...p} variant="default" leftSection={<IconUpload size={16} />} loading={busy === 'import'}>
                Import
              </Button>
            )}
          </FileButton>
          <Button variant="default" leftSection={<IconApi size={16} />} component="a" href="/api/openapi.json" target="_blank">
            OpenAPI of all live integrations
          </Button>
          <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
            New integration
          </Button>
        </Group>
      </Group>

      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} title="Could not load integrations" role="alert">
          {error}
        </Alert>
      )}

      {items === null && !error && <Skeleton height={120} />}

      {items?.length === 0 && (
        <Stack align="flex-start" gap="sm">
          <Text>No integrations yet. An integration connects one of your payload definitions to one operation of an external API.</Text>
          <Button variant="light" onClick={() => setCreating(true)}>
            New integration
          </Button>
        </Stack>
      )}

      {items && items.length > 0 && <IntegrationTable items={items} onDelete={setDeleting} picked={picked} onPick={setPicked} />}

      <NewIntegrationModal opened={creating} onClose={() => setCreating(false)} />
      <Modal opened={!!imported} onClose={() => setImported(null)} title="Imported" size="lg">
        {imported && <ImportResult report={imported} onChanged={load} />}
      </Modal>
      <Modal opened={!!refused} onClose={() => setRefused(null)} title="Nothing was imported" size="lg">
        <Stack>
          <Text size="sm">{refused?.message}</Text>
          {refused && refused.problems.length > 0 && (
            <List size="sm">
              {refused.problems.map((p) => (
                <List.Item key={p}>{p}</List.Item>
              ))}
            </List>
          )}
        </Stack>
      </Modal>
      <Modal opened={!!deleting} onClose={() => setDeleting(null)} title="Delete this integration?">
        <Stack>
          <Text>
            “{deleting?.name ?? deleting?.id}” is deleted with all its versions and its stored secret.
            {deleting?.activeVersion && (
              <>
                {' '}
                Its live endpoint <Code>/api/v1/integrations/{deleting.id}</Code> stops answering.
              </>
            )}{' '}
            Payload definitions and Swagger configurations stay.
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

function IntegrationTable({
  items,
  onDelete,
  picked,
  onPick,
}: {
  items: IntegrationSummary[];
  onDelete: (it: IntegrationSummary) => void;
  picked: Set<string>;
  onPick: (picked: Set<string>) => void;
}) {
  const navigate = useNavigate();
  const toggle = (id: string) => {
    const next = new Set(picked);
    if (!next.delete(id)) next.add(id);
    onPick(next);
  };
  const all = items.every((it) => picked.has(it.id));
  return (
    <Table highlightOnHover verticalSpacing="sm">
      <Table.Thead>
        <Table.Tr>
          <Table.Th w={40}>
            <Checkbox
              aria-label="Pick all for export"
              checked={all}
              indeterminate={!all && picked.size > 0}
              onChange={() => onPick(all ? new Set() : new Set(items.map((it) => it.id)))}
            />
          </Table.Th>
          <Table.Th>Name</Table.Th>
          <Table.Th>Input → Output</Table.Th>
          <Table.Th>Method</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th>Live version</Table.Th>
          <Table.Th />
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {items.map((it) => (
          <Table.Tr
            key={it.id}
            style={{ cursor: 'pointer' }}
            tabIndex={0}
            onClick={() => navigate(`/integrations/${it.id}`)}
            onKeyDown={(e) => e.key === 'Enter' && navigate(`/integrations/${it.id}`)}
          >
            <Table.Td onClick={(e) => e.stopPropagation()}>
              <Checkbox aria-label={`Pick ${it.name ?? it.id} for export`} checked={picked.has(it.id)} onChange={() => toggle(it.id)} />
            </Table.Td>
            <Table.Td>
              <Text fw={500}>{it.name ?? it.id}</Text>
              <Code>{it.id}</Code>
            </Table.Td>
            <Table.Td>
              <Text ff="monospace" size="sm">
                {it.input ?? '—'} → {it.output ?? '—'}
              </Text>
            </Table.Td>
            <Table.Td>{it.method ?? '—'}</Table.Td>
            <Table.Td>
              <Badge color={STATUS[it.status].color} variant="light" title={it.problem}>
                {STATUS[it.status].label}
              </Badge>
            </Table.Td>
            <Table.Td>
              {it.activeVersion ?? '—'}
              {it.activeVersion && (
                <Anchor href={`/api/openapi/${it.id}.json`} target="_blank" size="xs" ml="xs" onClick={(e) => e.stopPropagation()}>
                  OpenAPI
                </Anchor>
              )}
            </Table.Td>
            <Table.Td>
              <Group gap="xs" justify="flex-end" wrap="nowrap">
                <Button size="compact-sm" variant="default" onClick={() => navigate(`/integrations/${it.id}`)}>
                  Select
                </Button>
                <Button
                  size="compact-sm"
                  variant="subtle"
                  color={meaning.broken}
                  leftSection={<IconTrash size={14} />}
                  onClick={(e) => {
                    e.stopPropagation();
                    onDelete(it);
                  }}
                >
                  Delete
                </Button>
              </Group>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function NewIntegrationModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const slug = slugify(name);

  const create = async () => {
    setBusy(true);
    setError(null);
    try {
      const draft = await api.createIntegration(name);
      navigate(`/integrations/${draft.id}`);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal opened={opened} onClose={onClose} title="New integration">
      <Stack>
        <TextInput
          label="Name"
          description="What the integration does, for example “Register person”"
          value={name}
          onChange={(e) => setName(e.currentTarget.value)}
          onKeyDown={(e) => e.key === 'Enter' && slug && create()}
          data-autofocus
        />
        <Text size="sm" c="dimmed">
          Address: <Code>/api/v1/integrations/{slug || '…'}</Code>
        </Text>
        {error && (
          <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
            {error}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={create} disabled={!slug} loading={busy}>
            Create
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
