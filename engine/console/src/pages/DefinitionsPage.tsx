import { useEffect, useState } from 'react';
import { Alert, Button, Code, Group, Skeleton, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { IconAlertTriangle, IconPlus, IconSearch } from '@tabler/icons-react';
import { Link, useNavigate } from 'react-router-dom';
import { api } from '../api';
import type { DefinitionSummary } from '../model';
import { meaning } from '../theme';

/** The payload library (D-8): reusable shapes, with where each one is used. */
export function DefinitionsPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<DefinitionSummary[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState('');

  useEffect(() => {
    api.definitions().then(setItems, (e: Error) => setError(e.message));
  }, []);

  const q = query.trim().toLowerCase();
  const shown = items?.filter((d) => !q || d.id.includes(q) || (d.name ?? '').toLowerCase().includes(q));

  return (
    <Stack gap="lg" maw={1100}>
      <Group justify="space-between">
        <Title order={2}>Payload definitions</Title>
        <Button leftSection={<IconPlus size={16} />} component={Link} to="/definitions/new">
          New payload definition
        </Button>
      </Group>

      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
          {error}
        </Alert>
      )}
      {items === null && !error && <Skeleton height={120} />}

      {items?.length === 0 && (
        <Stack align="flex-start" gap="sm">
          <Text>No payload definitions yet. A payload definition is the shape of the data your systems send or get back.</Text>
          <Button variant="light" component={Link} to="/definitions/new">
            New payload definition
          </Button>
        </Stack>
      )}

      {items && items.length > 0 && (
        <>
          <TextInput
            placeholder="Search by name or id"
            leftSection={<IconSearch size={16} />}
            value={query}
            onChange={(e) => setQuery(e.currentTarget.value)}
            maw={360}
            aria-label="Search payload definitions"
          />
          <Table highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Fields</Table.Th>
                <Table.Th>Used by</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {shown?.map((d) => (
                <Table.Tr
                  key={d.id}
                  style={{ cursor: 'pointer' }}
                  tabIndex={0}
                  onClick={() => navigate(`/definitions/${d.id}`)}
                  onKeyDown={(e) => e.key === 'Enter' && navigate(`/definitions/${d.id}`)}
                >
                  <Table.Td>
                    <Text fw={500}>{d.name ?? d.id}</Text>
                    <Code>{d.id}</Code>
                  </Table.Td>
                  <Table.Td>{d.fieldCount}</Table.Td>
                  <Table.Td>
                    <Text size="sm" ff="monospace">
                      {d.usedBy.length ? d.usedBy.join(', ') : '—'}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {shown?.length === 0 && <Text c="dimmed">No payload definition matches “{query}”.</Text>}
        </>
      )}
    </Stack>
  );
}
