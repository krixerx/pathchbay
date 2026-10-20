import { useEffect, useState } from 'react';
import { Alert, Anchor, Button, Group, Select, Stack, Text } from '@mantine/core';
import { IconAlertTriangle, IconCheck } from '@tabler/icons-react';
import { Link } from 'react-router-dom';
import { api } from '../../api';
import type { DefinitionSummary, IntegrationDraft } from '../../model';
import { meaning } from '../../theme';

/** Step 1: which payload definitions callers send (Input) and get back (Output) (D-8, D-9). Autosaved (D-1). */
export function PayloadStep(props: { draft: IntegrationDraft; onChange: (d: IntegrationDraft) => void; onNext: () => void }) {
  const { draft, onChange } = props;
  const [definitions, setDefinitions] = useState<DefinitionSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<'idle' | 'saving' | 'saved'>('idle');

  useEffect(() => {
    api.definitions().then(setDefinitions, (e: Error) => setError(e.message));
  }, []);

  const choose = async (part: 'input' | 'output', value: string | null) => {
    const next = { ...draft, [part]: value ?? undefined };
    onChange(next);
    setSaved('saving');
    setError(null);
    try {
      onChange(await api.setPayloads(draft.id, next.input ?? null, next.output ?? null));
      setSaved('saved');
    } catch (e) {
      setError((e as Error).message);
      setSaved('idle');
    }
  };

  const options = definitions.map((d) => ({
    value: d.id,
    label: `${d.name ?? d.id} (${d.id}) · ${d.fieldCount} fields${d.usedBy.length ? ` · used by ${d.usedBy.length}` : ''}`,
  }));

  return (
    <Stack gap="md" mt="lg" maw={640}>
      <Text>
        Pick what callers send (Input) and what they get back (Output). Both come from the payload library; the same
        definition can be used for both.
      </Text>
      <Select
        label="Input"
        description="What callers send to this integration"
        placeholder="Choose a payload definition"
        data={options}
        value={draft.input ?? null}
        onChange={(v) => choose('input', v)}
        searchable
        clearable
        nothingFoundMessage="No payload definition with that name"
      />
      <Select
        label="Output"
        description="What callers get back"
        placeholder="Choose a payload definition"
        data={options}
        value={draft.output ?? null}
        onChange={(v) => choose('output', v)}
        searchable
        clearable
        nothingFoundMessage="No payload definition with that name"
      />
      <Group justify="space-between">
        <Anchor component={Link} to="/definitions/new" size="sm">
          Create a new payload definition
        </Anchor>
        <Text size="sm" c="dimmed" aria-live="polite">
          {saved === 'saving' && 'Saving…'}
          {saved === 'saved' && (
            <Group gap={4} component="span">
              <IconCheck size={14} /> Saved
            </Group>
          )}
        </Text>
      </Group>
      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
          {error}
        </Alert>
      )}
      <Group>
        <Button onClick={props.onNext} disabled={!draft.input || !draft.output}>
          Next: import the contract
        </Button>
      </Group>
    </Stack>
  );
}
