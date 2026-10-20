import { useEffect, useState } from 'react';
import { Alert, Anchor, Code, Group, Select, Skeleton, Stack, Stepper, Text, Title } from '@mantine/core';
import { IconAlertTriangle, IconCheck } from '@tabler/icons-react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import type { DefinitionSummary, IntegrationDraft } from '../model';
import { meaning } from '../theme';

/** One integration: the 5 steps (D-2, D-19). Step 1 picks the Input and Output payload definitions (D-8, D-9). */
export function IntegrationPage() {
  const { slug = '' } = useParams();
  return <Integration key={slug} slug={slug} />;
}

function Integration({ slug }: { slug: string }) {
  const [draft, setDraft] = useState<IntegrationDraft | null>(null);
  const [definitions, setDefinitions] = useState<DefinitionSummary[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState<'idle' | 'saving' | 'saved'>('idle');

  useEffect(() => {
    Promise.all([api.integration(slug), api.definitions()]).then(
      ([d, defs]) => {
        setDraft(d);
        setDefinitions(defs);
      },
      (e: Error) => setError(e.message),
    );
  }, [slug]);

  // Autosave on every change (D-1).
  const choose = async (part: 'input' | 'output', value: string | null) => {
    if (!draft) return;
    const next = { ...draft, [part]: value ?? undefined };
    setDraft(next);
    setSaved('saving');
    setError(null);
    try {
      setDraft(await api.setPayloads(slug, next.input ?? null, next.output ?? null));
      setSaved('saved');
    } catch (e) {
      setError((e as Error).message);
      setSaved('idle');
    }
  };

  if (!draft) {
    return error ? (
      <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
        {error}
      </Alert>
    ) : (
      <Skeleton height={200} />
    );
  }

  const options = definitions.map((d) => ({
    value: d.id,
    label: `${d.name ?? d.id} (${d.id}) · ${d.fieldCount} fields${d.usedBy.length ? ` · used by ${d.usedBy.length}` : ''}`,
  }));

  return (
    <Stack gap="lg" maw={1100}>
      <div>
        <Title order={2}>{draft.name ?? draft.id}</Title>
        <Text size="sm" c="dimmed">
          <Code>/api/v1/integrations/{draft.id}</Code>
          {draft.activeVersion ? ` · live version ${draft.activeVersion}` : ' · not published yet'}
        </Text>
      </div>

      <Stepper active={0} allowNextStepsSelect={false} size="sm">
        <Stepper.Step label="Payload" description="Input and Output">
          <Stack gap="md" mt="lg" maw={640}>
            <Text>
              Pick what callers send (Input) and what they get back (Output). Both come from the payload library; the
              same definition can be used for both.
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
          </Stack>
        </Stepper.Step>
        <Stepper.Step label="Import" description="Swagger file" />
        <Stepper.Step label="Operation" description="What to call" />
        <Stepper.Step label="Map" description="Link fields" />
        <Stepper.Step label="Publish" description="Make it live" />
      </Stepper>

      <Text size="sm" c="dimmed">
        Steps 2–5 come in the next build steps. Until then, the rest of an integration is edited in{' '}
        <Code>config/services/{draft.id}.json</Code> and published with{' '}
        <Code>POST /admin/integrations/{draft.id}/publish</Code>.
      </Text>
    </Stack>
  );
}
