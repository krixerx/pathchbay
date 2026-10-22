import { useEffect, useState } from 'react';
import {
  Alert,
  Anchor,
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
 * The integration's connection settings, outside the stepper (D-3): how the external system is authenticated, and the
 * secret. The base URL belongs to the Swagger configuration's connection (D-26) and is only shown here; files from
 * before connections still set it here. The secret is never shown again after saving; the page says where it comes from.
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
    setBaseUrl(s.baseUrl ?? '');
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

  const save = async () => {
    setSaving(true);
    setError(null);
    setSaved(false);
    try {
      load(await api.saveSettings(slug, { baseUrl: settings.connection ? undefined : baseUrl, auth, secret: secret || undefined }));
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

      {settings.connection ? (
        <Stack gap={4}>
          <Text fw={500} size="sm">
            Base URL
          </Text>
          {settings.baseUrl ? (
            <Code>{settings.baseUrl}</Code>
          ) : (
            <Text size="sm" c="yellow.8">
              Not set yet: needed before the integration can be tested for real or published.
            </Text>
          )}
          <Text size="sm" c="dimmed">
            From the connection <Code>{settings.connection}</Code>, shared by every integration calling this system. Change it under{' '}
            <Anchor component={Link} to="/configuration" size="sm">
              Configuration
            </Anchor>
            .
          </Text>
        </Stack>
      ) : (
        <TextInput
          label="Base URL"
          description="This integration is from before connections; pick its operation again to use the Swagger configuration's connection."
          placeholder="https://api.example.org"
          value={baseUrl}
          onChange={(e) => setBaseUrl(e.currentTarget.value)}
          styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
        />
      )}

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
