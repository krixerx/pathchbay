import { useEffect, useState } from 'react';
import {
  ActionIcon,
  Alert,
  Autocomplete,
  Button,
  Checkbox,
  Code,
  CopyButton,
  Group,
  PasswordInput,
  Skeleton,
  Stack,
  Text,
  TextInput,
} from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconPlus, IconX } from '@tabler/icons-react';
import { api } from '../api';
import type { Connection } from '../model';
import { meaning } from '../theme';

/** A header row being edited; `value` stays empty for a saved secret until the user types a new one. */
interface HeaderRow {
  name: string;
  value: string;
  secret: boolean;
  saved: boolean;
  envName?: string;
  inEnv: boolean;
}

const mono = { input: { fontFamily: 'var(--mantine-font-family-monospace)' } };

/**
 * The connection of one Swagger configuration (D-26): where its external system is in this environment, and the
 * headers sent on every call to it (D-27). Every integration calling it uses these, live ones at once after saving.
 * Environment variables always win.
 */
export function ConnectionForm(props: { id: string; onSaved: () => void }) {
  const [connection, setConnection] = useState<Connection | null>(null);
  const [url, setUrl] = useState('');
  const [headers, setHeaders] = useState<HeaderRow[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const load = (c: Connection) => {
    setConnection(c);
    setUrl(c.savedUrl ?? '');
    setHeaders(c.headers.map((h) => ({ name: h.name, value: h.value ?? '', secret: h.secret, saved: h.saved, envName: h.envName, inEnv: h.inEnv })));
  };
  const change = (i: number, patch: Partial<HeaderRow>) => setHeaders((old) => old.map((h, j) => (j === i ? { ...h, ...patch } : h)));

  useEffect(() => {
    api.connection(props.id).then(load, (e: Error) => setError(e.message));
  }, [props.id]);

  if (!connection) return error ? <Alert color={meaning.broken}>{error}</Alert> : <Skeleton height={120} />;

  const save = async () => {
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      load(await api.saveConnection(props.id, url.trim(), headers.map((h) => ({ name: h.name.trim(), value: h.value, secret: h.secret }))));
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
      <Stack gap={6} mt="xs">
        <Text size="sm" fw={500}>
          Headers
        </Text>
        <Text size="sm" c="dimmed">
          Sent on every call to this system, such as a client id. Mark keys and tokens as secret: they are kept with the other
          secrets and shown as ***. A header whose value comes from the Input is added on the integration’s board instead.
        </Text>
        {headers.map((h, i) => (
          <Stack key={i} gap={2}>
            <Group gap="xs" align="flex-start" wrap="nowrap">
              <TextInput
                aria-label="Header name"
                placeholder="X-Client-Id"
                value={h.name}
                onChange={(e) => change(i, { name: e.currentTarget.value })}
                style={{ flex: 1 }}
                styles={mono}
              />
              {h.secret ? (
                <PasswordInput
                  aria-label={`Value of ${h.name || 'the header'}`}
                  placeholder={h.inEnv ? 'Set by the environment' : h.saved ? 'Saved; type to replace' : 'Value'}
                  value={h.value}
                  onChange={(e) => change(i, { value: e.currentTarget.value })}
                  style={{ flex: 1 }}
                />
              ) : (
                <TextInput
                  aria-label={`Value of ${h.name || 'the header'}`}
                  placeholder={h.inEnv ? 'Set by the environment' : 'Value'}
                  value={h.value}
                  onChange={(e) => change(i, { value: e.currentTarget.value })}
                  style={{ flex: 1 }}
                  styles={mono}
                />
              )}
              <Checkbox
                label="Secret"
                mt={8}
                checked={h.secret}
                onChange={(e) => change(i, { secret: e.currentTarget.checked, value: '', saved: false })}
              />
              <ActionIcon
                variant="subtle"
                color="gray"
                mt={4}
                aria-label={`Remove header ${h.name}`}
                onClick={() => setHeaders((old) => old.filter((_, j) => j !== i))}
              >
                <IconX size={14} />
              </ActionIcon>
            </Group>
            {h.envName && (
              <Text size="xs" c="dimmed">
                {h.inEnv ? 'Set by the environment variable (this always wins): ' : 'Or set it in the engine’s environment: '}
                <Code>{h.envName}</Code>
              </Text>
            )}
          </Stack>
        ))}
        <Group>
          <Button
            variant="subtle"
            size="compact-sm"
            leftSection={<IconPlus size={14} />}
            onClick={() => setHeaders((old) => [...old, { name: '', value: '', secret: false, saved: false, inEnv: false }])}
          >
            Add header
          </Button>
        </Group>
      </Stack>
      <Text size="sm" c="dimmed">
        Used by: {connection.usedBy.length ? connection.usedBy.join(', ') : 'no integration yet'}
      </Text>
      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} title="Not saved" role="alert">
          {error}
        </Alert>
      )}
      <Group>
        <Button onClick={save} loading={saving}>
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
