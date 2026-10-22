import { useEffect, useState } from 'react';
import { Accordion, Alert, Badge, Code, Drawer, Group, Stack, Text } from '@mantine/core';
import { IconAlertTriangle, IconInfoCircle } from '@tabler/icons-react';
import { api } from '../../api';
import { CallSteps } from '../../components/CallSteps';
import type { CallList } from '../../model';
import { meaning } from '../../theme';

const REFRESH_MS = 3000;

/**
 * The call log (D-22): the last live calls of this integration, newest first, refreshed while open. A call opens
 * the same steps as the Test drawer, plus what the caller sent.
 */
export function CallsDrawer(props: { slug: string; opened: boolean; onClose: () => void }) {
  const [data, setData] = useState<CallList | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    if (!props.opened) return;
    const load = () =>
      api.calls(props.slug).then(
        (d) => {
          setData(d);
          setError(null);
        },
        (e: Error) => setError(e.message),
      );
    load();
    const timer = setInterval(load, REFRESH_MS);
    return () => clearInterval(timer);
  }, [props.opened, props.slug]);

  return (
    <Drawer opened={props.opened} onClose={props.onClose} position="right" size="xl" title="Calls">
      <Stack gap="sm">
        {error && (
          <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
            {error}
          </Alert>
        )}
        {data && !data.enabled && (
          <Alert color={meaning.neutral} icon={<IconInfoCircle />}>
            The call log is off (<Code>MAPPER_CALL_LOG=off</Code>). Each call still writes one line to the container log.
          </Alert>
        )}
        {data?.enabled && (
          <Text size="sm" c="dimmed">
            The last {data.calls.length === 0 ? '' : `${data.calls.length} `}live calls, newest first. Kept in memory only, so a
            restart clears them.
          </Text>
        )}
        {data?.enabled && data.calls.length === 0 && <Text>No calls since the engine started.</Text>}
        <Accordion value={open} onChange={setOpen} variant="separated">
          {data?.calls.map((c) => {
            return (
              <Accordion.Item key={c.at} value={c.at}>
                <Accordion.Control>
                  <Group gap="sm" wrap="nowrap">
                    <Text size="sm" ff="monospace">
                      {new Date(c.at).toLocaleTimeString()}
                    </Text>
                    <Badge color={c.status < 400 ? meaning.accent : meaning.broken} variant="light">
                      {c.status}
                    </Badge>
                    <Text size="sm" c={c.stage === 'done' ? undefined : 'red.7'}>
                      {c.stage === 'done' ? 'went through' : `stopped at ${c.stage}`}
                    </Text>
                    <Text size="sm" c="dimmed" ml="auto">
                      {c.durationMs} ms · version {c.version ?? '?'}
                    </Text>
                  </Group>
                </Accordion.Control>
                <Accordion.Panel>
                  <CallSteps result={c} passed="Went through" />
                </Accordion.Panel>
              </Accordion.Item>
            );
          })}
        </Accordion>
      </Stack>
    </Drawer>
  );
}
