import { useEffect, useState } from 'react';
import { Alert, Autocomplete, Button, Code, CopyButton, Group, Skeleton, Stack, Text } from '@mantine/core';
import { IconAlertTriangle, IconCheck } from '@tabler/icons-react';
import { api } from '../api';
import type { Connection } from '../model';
import { meaning } from '../theme';

/**
 * The connection of one Swagger configuration (D-26): where its external system is in this environment. Every
 * integration calling it uses this address, live ones at once after saving. The environment variable always wins.
 */
export function ConnectionForm(props: { id: string; onSaved: () => void }) {
  const [connection, setConnection] = useState<Connection | null>(null);
  const [url, setUrl] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const load = (c: Connection) => {
    setConnection(c);
    setUrl(c.savedUrl ?? '');
  };

  useEffect(() => {
    api.connection(props.id).then(load, (e: Error) => setError(e.message));
  }, [props.id]);

  if (!connection) return error ? <Alert color={meaning.broken}>{error}</Alert> : <Skeleton height={120} />;

  const save = async () => {
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      load(await api.saveConnection(props.id, url.trim()));
      setSaved(true);
      props.onSaved();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  return (
    <Stack gap="sm">
      <Text size="sm">
        Where this external system is in this environment. Integrations and their versions only name the connection, so they can
        move between environments unchanged; each environment sets its own address.
      </Text>
      <Autocomplete
        label="Base URL"
        description={connection.servers.some((s) => /^https?:\/\//.test(s)) ? 'Suggested from the Swagger file; you can type another address' : undefined}
        placeholder="https://api.example.org"
        data={connection.servers.filter((s) => /^https?:\/\//.test(s))}
        value={url}
        onChange={setUrl}
        disabled={connection.inEnv}
        styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
      />
      <Group gap="xs">
        <Text size="sm" c="dimmed">
          {connection.inEnv ? 'Set by the environment variable (this always wins):' : 'Or set it in the engine’s environment:'}
        </Text>
        <Code>{connection.envName}</Code>
        <CopyButton value={connection.envName}>
          {({ copied, copy }) => (
            <Button size="compact-xs" variant="subtle" onClick={copy}>
              {copied ? 'Copied' : 'Copy'}
            </Button>
          )}
        </CopyButton>
      </Group>
      {connection.inEnv && (
        <Text size="sm">
          In use: <Code>{connection.baseUrl}</Code>
        </Text>
      )}
      <Text size="sm" c="dimmed">
        Used by: {connection.usedBy.length ? connection.usedBy.join(', ') : 'no integration yet'}
      </Text>
      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} title="Not saved" role="alert">
          {error}
        </Alert>
      )}
      <Group>
        <Button onClick={save} loading={saving} disabled={connection.inEnv}>
          Save
        </Button>
        {saved && (
          <Group gap={4} aria-live="polite">
            <IconCheck size={14} />
            <Text size="sm">Saved; live integrations use it now</Text>
          </Group>
        )}
      </Group>
    </Stack>
  );
}
