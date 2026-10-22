import { useEffect, useState } from 'react';
import { Alert, Anchor, Badge, Button, Code, CopyButton, Group, Paper, Skeleton, Stack, Table, Text, Title } from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconX } from '@tabler/icons-react';
import { api } from '../../api';
import type { Tab } from '../../board';
import type { ContractOperation, IntegrationDraft, PayloadDefinition, VersionList } from '../../model';
import type { Problem } from '../../publish';
import { curlCommand, publishSummary } from '../../publish';
import { meaning } from '../../theme';

interface BoardData {
  input: PayloadDefinition | null;
  output: PayloadDefinition | null;
  operation: ContractOperation | null;
}

/** Step 5 (D-6, D-10, R8): summary, publish as the next version, live URL + curl, earlier versions with "Make active". */
export function PublishStep(props: {
  draft: IntegrationDraft;
  onChange: (d: IntegrationDraft) => void;
  onShow: (tab: Tab) => void;
}) {
  const { draft } = props;
  const [data, setData] = useState<BoardData | null>(null);
  const [versions, setVersions] = useState<VersionList | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [published, setPublished] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.board(draft.id).then(setData, (e: Error) => setError(e.message));
    api.versions(draft.id).then(setVersions, (e: Error) => setError(e.message));
  }, [draft.id]);

  if (!data || !versions) return error ? <ErrorBox text={error} /> : <Skeleton height={200} mt="lg" />;

  const { blocking, warnings } = publishSummary(draft, data.input, data.output, data.operation);
  const method = data.operation?.method ?? draft.target?.method ?? 'POST';
  const live = versions.activeVersion;

  const run = async (label: string, action: () => Promise<VersionList>) => {
    setBusy(label);
    setError(null);
    try {
      const v = await action();
      setVersions(v);
      props.onChange({ ...draft, activeVersion: v.activeVersion ?? undefined });
      return v;
    } catch (e) {
      setError((e as Error).message);
      return null;
    } finally {
      setBusy(null);
    }
  };

  const publish = async () => {
    const v = await run('publish', () => api.publish(draft.id));
    if (v) setPublished(v.activeVersion);
  };

  return (
    <Stack gap="md" mt="md" maw={900}>
      <Paper withBorder p="md">
        <Stack gap="sm">
          <Title order={4}>Before you publish</Title>
          {blocking.length === 0 && warnings.length === 0 && (
            <Group gap={6}>
              <IconCheck size={16} color="var(--mantine-color-teal-7)" />
              <Text size="sm">All links are valid and every required field is mapped.</Text>
            </Group>
          )}
          <ProblemList
            title={`${blocking.length} link${blocking.length === 1 ? '' : 's'} must be fixed or deleted before publishing`}
            items={blocking}
            color={meaning.broken}
            icon={<IconX size={16} />}
            onShow={props.onShow}
          />
          <ProblemList
            title={`${warnings.length} warning${warnings.length === 1 ? '' : 's'} (publishing is still possible)`}
            items={warnings}
            color={meaning.warning}
            icon={<IconAlertTriangle size={16} />}
            onShow={props.onShow}
          />
          <Group>
            <Button onClick={publish} loading={busy === 'publish'} disabled={blocking.length > 0 || busy !== null}>
              {live ? 'Publish as next version' : 'Publish'}
            </Button>
            {blocking.length > 0 && (
              <Text size="sm" c="dimmed">
                Publishing is possible once the red links are fixed.
              </Text>
            )}
          </Group>
          {busy === 'publish' && <Text size="sm">Publishing…</Text>}
        </Stack>
      </Paper>

      {error && <ErrorBox text={`${error} The draft is kept; nothing changed for callers.`} />}

      {live && (
        <Paper withBorder p="md">
          <Stack gap="xs">
            <Group gap="xs">
              <Title order={4}>Live</Title>
              <Badge color={meaning.accent} variant="light">
                version {live}
              </Badge>
              {published && (
                <Text size="sm" c="teal.8">
                  Just published
                </Text>
              )}
            </Group>
            <Text size="sm">
              {method} <Code>{`${window.location.origin}/api/v1/integrations/${draft.id}`}</Code>
            </Text>
            <Text size="sm" c="dimmed">
              Try it with:
            </Text>
            <Group align="flex-start" wrap="nowrap" gap="xs">
              <Code block style={{ flex: 1, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                {curlCommand(window.location.origin, draft.id, method, data.input)}
              </Code>
              <CopyButton value={curlCommand(window.location.origin, draft.id, method, data.input)}>
                {({ copied, copy }) => (
                  <Button size="xs" variant="default" onClick={copy}>
                    {copied ? 'Copied' : 'Copy'}
                  </Button>
                )}
              </CopyButton>
            </Group>
          </Stack>
        </Paper>
      )}

      {versions.versions.length > 0 && (
        <Paper withBorder p="md">
          <Title order={4} mb="xs">
            Versions
          </Title>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Version</Table.Th>
                <Table.Th>Published</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {versions.versions.map((v) => (
                <Table.Tr key={v.version}>
                  <Table.Td>
                    <Code>{v.version}</Code>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{v.publishedAt ? new Date(v.publishedAt).toLocaleString() : ''}</Text>
                  </Table.Td>
                  <Table.Td ta="right">
                    {v.version === live ? (
                      <Badge color={meaning.accent} variant="light">
                        Live
                      </Badge>
                    ) : (
                      <Button
                        size="compact-sm"
                        variant="default"
                        loading={busy === v.version}
                        disabled={busy !== null}
                        onClick={() => run(v.version, () => api.activate(draft.id, v.version))}
                      >
                        Make active
                      </Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Text size="xs" c="dimmed" mt="xs">
            Make active serves that version again without creating a new one. The next publish still takes the next number.
          </Text>
        </Paper>
      )}
    </Stack>
  );
}

function ProblemList(props: { title: string; items: Problem[]; color: string; icon: React.ReactNode; onShow: (tab: Tab) => void }) {
  if (props.items.length === 0) return null;
  return (
    <Alert color={props.color} icon={props.icon} title={props.title} p="sm">
      <Stack gap={2}>
        {props.items.map((p) => (
          <Group key={`${p.tab}:${p.text}`} gap="xs" wrap="nowrap">
            <Text size="sm" ff="monospace">
              {p.text}
            </Text>
            <Anchor size="sm" component="button" onClick={() => props.onShow(p.tab)}>
              Show
            </Anchor>
          </Group>
        ))}
      </Stack>
    </Alert>
  );
}

function ErrorBox({ text }: { text: string }) {
  return (
    <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
      {text}
    </Alert>
  );
}
