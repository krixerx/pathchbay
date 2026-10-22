import { useEffect, useState } from 'react';
import { Alert, Badge, Button, Code, Group, Modal, Skeleton, Stack, Text, Title } from '@mantine/core';
import { IconAlertTriangle, IconFlask, IconListDetails, IconRocket, IconSettings } from '@tabler/icons-react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import type { Tab } from '../board';
import type { IntegrationDraft } from '../model';
import { meaning } from '../theme';
import { CallsDrawer } from './board/CallsDrawer';
import { MappingBoard } from './board/MappingBoard';
import { PublishPanel } from './board/PublishPanel';
import { SetupBar } from './board/SetupBar';

/**
 * One integration on one board, no wizard (user decision 2026-10-22, replaces the D-2 stepper): Input, Output and the
 * operation on top, the field mapping with lines below, Test, Publish and Settings in the header.
 */
export function IntegrationPage() {
  const { slug = '' } = useParams();
  return <Integration key={slug} slug={slug} />;
}

function Integration({ slug }: { slug: string }) {
  const [draft, setDraft] = useState<IntegrationDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('request');
  const [testOpen, setTestOpen] = useState(false);
  const [publishing, setPublishing] = useState(false);
  const [callsOpen, setCallsOpen] = useState(false);
  const [baseUrl, setBaseUrl] = useState<string | null>(null);

  useEffect(() => {
    api.integration(slug).then(setDraft, (e: Error) => setError(e.message));
  }, [slug]);

  // The base URL comes from the connection (D-26); fetched again when the operation (and so maybe the connection) changes.
  const connection = draft?.target?.connection;
  const operationId = draft?.operationId;
  const literalUrl = draft?.target?.baseUrl ?? null;
  useEffect(() => {
    if (!connection) {
      setBaseUrl(literalUrl);
      return;
    }
    api.connection(connection).then(
      (c) => setBaseUrl(c.baseUrl),
      () => setBaseUrl(null),
    );
  }, [connection, operationId, literalUrl]);

  if (!draft) {
    return error ? (
      <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
        {error}
      </Alert>
    ) : (
      <Skeleton height={200} />
    );
  }

  const linkCount = (draft.request?.links?.length ?? 0) + (draft.response?.links?.length ?? 0);
  const ready = !!(draft.input && draft.output && draft.operationId);

  return (
    <Stack gap="md">
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs">
            <Title order={2}>{draft.name ?? draft.id}</Title>
            {draft.activeVersion ? (
              <Badge color={meaning.accent} variant="light">
                live {draft.activeVersion}
              </Badge>
            ) : (
              <Badge color={meaning.neutral} variant="light">
                not published
              </Badge>
            )}
          </Group>
          <Text size="sm" c="dimmed">
            {draft.target?.method ?? 'POST'} <Code>/api/v1/integrations/{draft.id}</Code>
            {draft.target?.method && (
              <>
                {' → calls '}
                <Code>
                  {draft.target.method} {baseUrl ?? '(no base URL yet)'}
                  {draft.target.path}
                </Code>
              </>
            )}
          </Text>
        </div>
        <Group gap="xs">
          <Button
            variant={testOpen ? 'light' : 'default'}
            leftSection={<IconFlask size={16} />}
            onClick={() => setTestOpen((o) => !o)}
            disabled={!ready}
          >
            Test
          </Button>
          <Button variant="default" leftSection={<IconListDetails size={16} />} onClick={() => setCallsOpen(true)}>
            Calls
          </Button>
          <Button leftSection={<IconRocket size={16} />} onClick={() => setPublishing(true)} disabled={!ready}>
            Publish
          </Button>
          <Button variant="default" leftSection={<IconSettings size={16} />} component={Link} to={`/integrations/${slug}/settings`}>
            Settings
          </Button>
        </Group>
      </Group>

      <SetupBar draft={draft} baseUrl={baseUrl} linkCount={linkCount} onChange={setDraft} />

      <MappingBoard
        key={`${draft.input}|${draft.output}|${draft.contract}|${draft.operationId}`}
        draft={draft}
        onChange={setDraft}
        tab={tab}
        onTab={setTab}
        testOpen={testOpen}
      />

      <Modal opened={publishing} onClose={() => setPublishing(false)} title="Publish" size="xl">
        <PublishPanel
          draft={draft}
          onChange={setDraft}
          onShow={(t) => {
            setTab(t);
            setPublishing(false);
          }}
        />
      </Modal>
      <CallsDrawer slug={slug} opened={callsOpen} onClose={() => setCallsOpen(false)} />
    </Stack>
  );
}
