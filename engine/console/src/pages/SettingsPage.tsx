import { useEffect, useState } from 'react';
import {
  Alert,
  Anchor,
  Autocomplete,
  Button,
  Code,
  CopyButton,
  Group,
  PasswordInput,
  SegmentedControl,
  Skeleton,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { IconAlertTriangle, IconCheck } from '@tabler/icons-react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import type { Auth, IntegrationSettings } from '../model';
import { meaning } from '../theme';

type AuthKind = 'none' | 'bearer' | 'apiKey';

/**
 * The integration's connection settings, outside the stepper (D-3): base URL, how the external system is
 * authenticated, and the secret. The secret is never shown again after saving; the page says where it comes from.
 */
export function SettingsPage() {
  const { slug = '' } = useParams();
  const [settings, setSettings] = useState<IntegrationSettings | null>(null);
  const [baseUrl, setBaseUrl] = useState('');
  const [kind, setKind] = useState<AuthKind>('none');
  const [keyIn, setKeyIn] = useState<'header' | 'query'>('header');
  const [keyName, setKeyName] = useState('');
  const [secret, setSecret] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const load = (s: IntegrationSettings) => {
    setSettings(s);
    setBaseUrl(s.baseUrl);
    setKind(s.auth ? s.auth.type : 'none');
    if (s.auth?.type === 'apiKey') {
      setKeyIn(s.auth.in);
      setKeyName(s.auth.name);
    }
  };

  useEffect(() => {
    api.settings(slug).then(load, (e: Error) => setError(e.message));
  }, [slug]);

  if (!settings) {
    return error ? (
      <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
        {error}
      </Alert>
    ) : (
      <Skeleton height={240} />
    );
  }

  const auth: Auth | null = kind === 'none' ? null : kind === 'bearer' ? { type: 'bearer' } : { type: 'apiKey', in: keyIn, name: keyName };
  const absoluteServers = settings.servers.filter((s) => /^https?:\/\//.test(s));

  const save = async () => {
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      load(await api.saveSettings(slug, { baseUrl, auth, secret: secret || undefined }));
      setSecret('');
      setSaved(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
    }
  };

  const secretSource = settings.secretInEnv
    ? `from the environment variable ${settings.secretEnvName} (this always wins)`
    : settings.secretInFile
      ? 'saved for this integration (git-ignored file)'
      : 'not set';

  return (
    <Stack gap="lg" maw={720}>
      <div>
        <Anchor component={Link} to={`/integrations/${slug}`} size="sm">
          ← Back to the integration
        </Anchor>
        <Title order={2} mt="xs">
          Settings
        </Title>
        <Text size="sm" c="dimmed">
          How the engine reaches the external system for <Code>{slug}</Code>.
        </Text>
      </div>

      <Autocomplete
        label="Base URL"
        description={
          absoluteServers.length
            ? 'Suggested from the contract; you can type another address'
            : 'The contract has no absolute server address, so type it here'
        }
        placeholder="https://api.example.org"
        data={absoluteServers}
        value={baseUrl}
        onChange={setBaseUrl}
        error={!baseUrl ? 'Needed before the integration can be tested or published' : undefined}
        styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
      />

      <Stack gap="xs">
        <Text fw={500} size="sm">
          Authentication
        </Text>
        <SegmentedControl
          value={kind}
          onChange={(v) => setKind(v as AuthKind)}
          data={[
            { value: 'none', label: 'None' },
            { value: 'bearer', label: 'Bearer token' },
            { value: 'apiKey', label: 'API key' },
          ]}
          w="fit-content"
        />
        {kind === 'apiKey' && (
          <Group align="flex-end">
            <SegmentedControl value={keyIn} onChange={(v) => setKeyIn(v as 'header' | 'query')} data={['header', 'query']} />
            <TextInput
              label={keyIn === 'header' ? 'Header name' : 'Query parameter name'}
              placeholder={keyIn === 'header' ? 'X-Api-Key' : 'api_key'}
              value={keyName}
              onChange={(e) => setKeyName(e.currentTarget.value)}
              error={!keyName.trim() ? 'Required' : undefined}
            />
          </Group>
        )}
      </Stack>

      {kind !== 'none' && (
        <Stack gap="xs">
          <PasswordInput
            label={kind === 'bearer' ? 'Token' : 'API key'}
            description={`Currently ${secretSource}. Leave empty to keep it.`}
            value={secret}
            onChange={(e) => setSecret(e.currentTarget.value)}
            autoComplete="off"
          />
          <Group gap="xs">
            <Text size="sm" c="dimmed">
              Or set it in the engine's environment:
            </Text>
            <Code>{settings.secretEnvName}</Code>
            <CopyButton value={settings.secretEnvName}>
              {({ copied, copy }) => (
                <Button size="compact-xs" variant="subtle" onClick={copy}>
                  {copied ? 'Copied' : 'Copy'}
                </Button>
              )}
            </CopyButton>
          </Group>
        </Stack>
      )}

      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} title="Not saved" role="alert">
          {error}
        </Alert>
      )}
      <Group>
        <Button onClick={save} loading={saving} disabled={kind === 'apiKey' && !keyName.trim()}>
          Save settings
        </Button>
        {saved && (
          <Group gap={4} aria-live="polite">
            <IconCheck size={14} />
            <Text size="sm">Saved</Text>
          </Group>
        )}
      </Group>
    </Stack>
  );
}
