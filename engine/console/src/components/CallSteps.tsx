import { Alert, Badge, Code, Group, Stack, Text } from '@mantine/core';
import { IconCheck, IconX } from '@tabler/icons-react';
import type { TestResult } from '../model';
import { meaning } from '../theme';

/**
 * One call step by step, as the Test drawer and the call log (D-22) show it: the outcome, problems, the request sent,
 * the answer and the mapped Output. A call that stopped early shows only the steps it reached.
 */
export function CallSteps(props: {
  result: Pick<TestResult, 'stage' | 'problems' | 'request' | 'response' | 'output'> & { mock?: boolean; input?: unknown };
  passed: string;
}) {
  const { result } = props;
  const ok = result.stage === 'done';
  return (
    <Stack gap={6} aria-live="polite">
      <Group gap={4}>
        {ok ? <IconCheck size={16} color="var(--mantine-color-green-7)" /> : <IconX size={16} color="var(--mantine-color-red-7)" />}
        <Text size="sm" c={ok ? 'green.8' : 'red.7'} fw={500}>
          {ok ? props.passed : `Stopped at: ${result.stage}`}
        </Text>
        {result.mock && <Badge variant="light" color="gray">mock</Badge>}
      </Group>
      {result.problems.length > 0 && (
        <Alert color={ok ? meaning.warning : meaning.broken} p="xs">
          {result.problems.map((p) => (
            <Text key={p} size="xs">
              {p}
            </Text>
          ))}
        </Alert>
      )}
      {result.input !== undefined && result.input !== null && <Block title="Caller sent">{show(result.input)}</Block>}
      {result.request && (
        <Block title="Request sent">
          {`${result.request.method} ${result.request.url}\n`}
          {Object.entries(result.request.headers)
            .map(([k, v]) => `${k}: ${v}`)
            .join('\n')}
          {result.request.body !== null && result.request.body !== undefined ? `\n\n${show(result.request.body)}` : ''}
        </Block>
      )}
      {result.response && (
        <Block title={`Answer ${result.response.status}`}>{show(result.response.body) ?? '(empty)'}</Block>
      )}
      {result.output !== undefined && result.output !== null && <Block title="Mapped to Output">{show(result.output)}</Block>}
    </Stack>
  );
}

/** JSON pretty-printed; an XML body arrives as text and is shown as it was sent. */
function show(v: unknown): string {
  return typeof v === 'string' ? v : JSON.stringify(v, null, 2);
}

function Block(props: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <Text size="xs" fw={600} c="dimmed">
        {props.title}
      </Text>
      <Code block style={{ maxHeight: 220, overflow: 'auto', fontSize: 12, whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
        {props.children}
      </Code>
    </div>
  );
}
