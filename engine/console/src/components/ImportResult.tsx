import { useState } from 'react';
import { Alert, Badge, Button, Code, Divider, Group, List, Stack, Text } from '@mantine/core';
import { IconAlertTriangle, IconCheck } from '@tabler/icons-react';
import { api } from '../api';
import type { ImportedIntegration, ImportReport } from '../model';
import { meaning } from '../theme';

/**
 * What an import stored (D-28). Nothing is live yet: each integration gets its own Make active, enabled once this
 * environment has what it needs (connection, secret, header values).
 */
export function ImportResult(props: { report: ImportReport; onChanged: () => void }) {
  const { report } = props;
  return (
    <Stack>
      <Text size="sm">
        Stored {report.integrations.length} {report.integrations.length === 1 ? 'integration' : 'integrations'}
        {report.exportedAt && ` exported ${new Date(report.exportedAt).toLocaleString()}`}. Nothing went live; make each one active
        when this environment is ready.
      </Text>
      {(report.definitions.length > 0 || report.contracts.length > 0) && (
        <Text size="sm" c="dimmed">
          Also written: {[...report.definitions.map((d) => `payload definition ${d}`), ...report.contracts.map((c) => `Swagger configuration ${c}`)].join(', ')}.
        </Text>
      )}
      {report.integrations.map((it) => (
        <Stack key={it.id} gap={0}>
          <Divider mb="sm" />
          <ImportedRow item={it} onChanged={props.onChanged} />
        </Stack>
      ))}
    </Stack>
  );
}

function ImportedRow(props: { item: ImportedIntegration; onChanged: () => void }) {
  const it = props.item;
  const [live, setLive] = useState(it.liveVersion);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const target = it.exportedActiveVersion;
  const canActivate = !!target && [...it.versionsAdded, ...it.versionsAlreadyHere].includes(target);

  const activate = async () => {
    if (!target) return;
    setBusy(true);
    setError(null);
    try {
      setLive((await api.activate(it.id, target)).activeVersion);
      props.onChanged();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="xs">
      <Group justify="space-between" wrap="nowrap" align="flex-start">
        <div>
          <Text fw={500}>{it.name}</Text>
          <Code>{it.id}</Code>
        </div>
        {live ? (
          <Badge color={meaning.accent} variant="light">
            Live {live}
          </Badge>
        ) : (
          <Badge color={meaning.neutral} variant="light">
            Not live here
          </Badge>
        )}
      </Group>
      <Text size="sm">
        {it.versionsAdded.length > 0 ? `Versions added: ${it.versionsAdded.join(', ')}.` : 'No new versions.'}
        {it.versionsAlreadyHere.length > 0 && ` Already here: ${it.versionsAlreadyHere.join(', ')}.`}
        {target ? ` Live where it came from: ${target}.` : ' It was not live where it came from.'}
      </Text>
      {it.needs.length > 0 && (
        <Alert color={meaning.warning} icon={<IconAlertTriangle />} title="Before it can go live here">
          <List size="sm">
            {it.needs.map((n) => (
              <List.Item key={n}>{n}</List.Item>
            ))}
          </List>
        </Alert>
      )}
      {it.warnings.map((w) => (
        <Text key={w} size="sm" c={meaning.warning}>
          {w}
        </Text>
      ))}
      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
          {error}
        </Alert>
      )}
      {canActivate && live !== target && (
        <Group>
          <Button size="compact-sm" onClick={activate} loading={busy} disabled={it.needs.length > 0}>
            Make {target} active
          </Button>
        </Group>
      )}
      {canActivate && live === target && (
        <Group gap={4} aria-live="polite">
          <IconCheck size={14} />
          <Text size="sm">Live here with the same version as where it came from</Text>
        </Group>
      )}
    </Stack>
  );
}
