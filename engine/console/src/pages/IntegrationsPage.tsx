import { useEffect, useState } from 'react';
import { Alert, Badge, Button, Code, Group, Modal, Skeleton, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { IconAlertTriangle, IconPlus } from '@tabler/icons-react';
import { useNavigate } from 'react-router-dom';
import { api } from '../api';
import type { IntegrationSummary } from '../model';
import { slugify } from '../tree';
import { meaning } from '../theme';

const STATUS: Record<IntegrationSummary['status'], { color: string; label: string }> = {
  live: { color: meaning.accent, label: 'Live' },
  draft: { color: meaning.neutral, label: 'Draft' },
  broken: { color: meaning.broken, label: 'Broken' },
};

/** Home (D-1): every integration with its status; "New integration" is the one primary action. */
export function IntegrationsPage() {
  const [items, setItems] = useState<IntegrationSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);

  useEffect(() => {
    api.integrations().then(setItems, (e: Error) => setError(e.message));
  }, []);

  return (
    <Stack gap="lg" maw={1100}>
      <Group justify="space-between">
        <Title order={2}>Integrations</Title>
        <Button leftSection={<IconPlus size={16} />} onClick={() => setCreating(true)}>
          New integration
        </Button>
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

      {items && items.length > 0 && <IntegrationTable items={items} />}

      <NewIntegrationModal opened={creating} onClose={() => setCreating(false)} />
    </Stack>
  );
}

function IntegrationTable({ items }: { items: IntegrationSummary[] }) {
  const navigate = useNavigate();
  return (
    <Table highlightOnHover verticalSpacing="sm">
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Name</Table.Th>
          <Table.Th>Input → Output</Table.Th>
          <Table.Th>Method</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th>Live version</Table.Th>
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
            <Table.Td>{it.activeVersion ?? '—'}</Table.Td>
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
