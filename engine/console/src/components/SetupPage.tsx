import { useEffect, useState, type ReactNode } from 'react';
import { Alert, Button, Center, Code, Loader, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { api, ApiError } from '../api';

/**
 * Shows the console only once the admin login exists. On a first start the engine has none yet: then this asks for
 * the setup code from the engine's log and creates the login. Afterwards the browser asks for it with its own dialog.
 */
export function SetupGate({ children }: { children: ReactNode }) {
  const [state, setState] = useState<'checking' | 'setup' | 'ready'>('checking');
  const [minLength, setMinLength] = useState(12);

  useEffect(() => {
    api
      .setupStatus()
      .then((s) => {
        setMinLength(s.minPasswordLength);
        setState(s.needed ? 'setup' : 'ready');
      })
      // Any other problem shows on the pages themselves, with their own messages.
      .catch(() => setState('ready'));
  }, []);

  if (state === 'checking') {
    return (
      <Center h="100vh">
        <Loader aria-label="Loading" />
      </Center>
    );
  }
  if (state === 'setup') {
    return <SetupPage minLength={minLength} onDone={() => window.location.reload()} />;
  }
  return <>{children}</>;
}

export function SetupPage({ minLength, onDone }: { minLength: number; onDone: () => void }) {
  const [code, setCode] = useState('');
  const [user, setUser] = useState('admin');
  const [password, setPassword] = useState('');
  const [repeat, setRepeat] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  const length = [...password].length;
  const tooShort = length > 0 && length < minLength;
  const differs = repeat.length > 0 && repeat !== password;
  const ready = code.trim() !== '' && user.trim() !== '' && length >= minLength && repeat === password;

  const submit = async () => {
    setSaving(true);
    setError(null);
    try {
      await api.createLogin(code.trim(), user.trim(), password);
      onDone();
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
      setSaving(false);
    }
  };

  return (
    <Center mih="100vh" p="lg">
      <Paper withBorder p="xl" w={440} component="main">
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (ready && !saving) void submit();
          }}
        >
          <Stack>
            <Title order={1} size="h3">
              Create admin login
            </Title>
            <Text size="sm">
              This is the first start, so Patchbay has no admin login yet. Enter the setup code the engine wrote to its log, for
              example with <Code>docker logs patchbay</Code>, then choose the user name and password for the console.
            </Text>
            {error && (
              <Alert color="red" title="Login not created">
                {error}
              </Alert>
            )}
            <TextInput label="Setup code" value={code} onChange={(e) => setCode(e.currentTarget.value)} required data-autofocus autoComplete="off" />
            <TextInput label="User name" value={user} onChange={(e) => setUser(e.currentTarget.value)} required autoComplete="username" />
            <PasswordInput
              label="Password"
              description={`At least ${minLength} characters`}
              value={password}
              onChange={(e) => setPassword(e.currentTarget.value)}
              error={tooShort ? `Use at least ${minLength} characters` : undefined}
              required
              autoComplete="new-password"
            />
            <PasswordInput
              label="Password again"
              value={repeat}
              onChange={(e) => setRepeat(e.currentTarget.value)}
              error={differs ? 'The passwords are not the same' : undefined}
              required
              autoComplete="new-password"
            />
            <Button type="submit" disabled={!ready} loading={saving}>
              Create login
            </Button>
            <Text size="xs" c="dimmed">
              The password is stored only as a hash. To start over, delete <Code>secrets/console/login.json</Code> in the
              config folder and restart the engine.
            </Text>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}
