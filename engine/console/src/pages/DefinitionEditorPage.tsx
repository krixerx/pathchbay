import { useEffect, useState } from 'react';
import { Alert, Button, Code, Group, List, Modal, Skeleton, Stack, Text, TextInput, Textarea, Title } from '@mantine/core';
import { IconAlertTriangle, IconClipboardText, IconInfoCircle } from '@tabler/icons-react';
import { useNavigate, useParams } from 'react-router-dom';
import { api } from '../api';
import type { PayloadDefinition } from '../model';
import { FieldTree } from '../components/FieldTree';
import { problems, slugify } from '../tree';
import { meaning } from '../theme';

/**
 * Create or edit one payload definition (D-13), with "used by" shown before saving a shared one (D-9).
 * Keyed by the route, so moving between definitions (or to "new") never carries one definition's state into another.
 */
export function DefinitionEditorPage() {
  const { id } = useParams();
  return <DefinitionEditor key={id ?? 'new'} id={id} />;
}

function DefinitionEditor({ id }: { id?: string }) {
  const isNew = !id;
  const navigate = useNavigate();
  const [def, setDef] = useState<PayloadDefinition | null>(isNew ? { id: '', name: '', fields: [] } : null);
  const [usedBy, setUsedBy] = useState<string[]>([]);
  const [guesses, setGuesses] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [pasting, setPasting] = useState(false);
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    if (isNew) return;
    api.definition(id).then(
      (r) => {
        setDef(r.definition);
        setUsedBy(r.usedBy);
      },
      (e: Error) => setError(e.message),
    );
  }, [id, isNew]);

  if (!def) {
    return error ? (
      <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
        {error}
      </Alert>
    ) : (
      <Skeleton height={240} />
    );
  }

  const change = (next: PayloadDefinition) => {
    setDef(next);
    setDirty(true);
  };
  const localProblems = [...(def.id ? [] : ['Give the definition a name']), ...problems(def.fields)];

  const save = async () => {
    setSaving(true);
    setError(null);
    try {
      const r = await api.saveDefinition(def);
      setDef(r.definition);
      setUsedBy(r.usedBy);
      setDirty(false);
      setGuesses([]);
      if (isNew) navigate(`/definitions/${r.definition.id}`, { replace: true });
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setSaving(false);
      setConfirming(false);
    }
  };

  return (
    <Stack gap="lg" maw={1200}>
      <Group justify="space-between" align="flex-end">
        <Stack gap={4}>
          <Title order={2}>{isNew ? 'New payload definition' : def.name || def.id}</Title>
          {!isNew && (
            <Text size="sm" c="dimmed">
              <Code>{def.id}</Code> · {usedBy.length ? `used by ${usedBy.join(', ')}` : 'not used by any integration yet'}
            </Text>
          )}
        </Stack>
        <Group>
          <Button variant="default" leftSection={<IconClipboardText size={16} />} onClick={() => setPasting(true)}>
            Paste a JSON sample
          </Button>
          <Button
            onClick={() => (usedBy.length ? setConfirming(true) : save())}
            disabled={localProblems.length > 0 || (!dirty && !isNew)}
            loading={saving}
          >
            Save
          </Button>
        </Group>
      </Group>

      <Group grow maw={720}>
        <TextInput
          label="Name"
          value={def.name}
          onChange={(e) => {
            const name = e.currentTarget.value;
            change({ ...def, name, id: isNew ? slugify(name) : def.id });
          }}
          required
        />
        <TextInput
          label="Id"
          description="Used in files and links; fixed after the first save"
          value={def.id}
          readOnly
          styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)' } }}
        />
      </Group>

      {guesses.length > 0 && (
        <Alert color={meaning.warning} icon={<IconInfoCircle />} title="Check these guesses from the sample" role="status">
          <List size="sm">
            {guesses.map((g) => (
              <List.Item key={g}>{g}</List.Item>
            ))}
          </List>
        </Alert>
      )}

      <FieldTree definitionId={def.id || 'new'} fields={def.fields} onChange={(fields) => change({ ...def, fields })} />

      {localProblems.length > 0 && (
        <Text size="sm" c={meaning.warning} role="status">
          Before saving: {localProblems.join('; ')}
        </Text>
      )}
      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} title="Not saved" role="alert">
          {error}
        </Alert>
      )}

      <Modal opened={confirming} onClose={() => setConfirming(false)} title="This definition is shared">
        <Stack>
          <Text>
            Used by {usedBy.length} integration{usedBy.length === 1 ? '' : 's'}: <Code>{usedBy.join(', ')}</Code>.
          </Text>
          <Text size="sm">
            Live versions keep the copy they were published with. The drafts of these integrations use the new shape,
            and links to fields you deleted will show a warning there.
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button onClick={save} loading={saving}>
              Save for all
            </Button>
          </Group>
        </Stack>
      </Modal>

      <PasteSampleModal
        opened={pasting}
        onClose={() => setPasting(false)}
        hasFields={def.fields.length > 0}
        onInferred={async (sample) => {
          const r = await api.infer(def.id || 'new', def.name, sample);
          change({ ...def, fields: r.definition.fields });
          setGuesses(r.guesses);
          setPasting(false);
        }}
      />
    </Stack>
  );
}

function PasteSampleModal(props: {
  opened: boolean;
  onClose: () => void;
  hasFields: boolean;
  onInferred: (sample: unknown) => Promise<void>;
}) {
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const use = async () => {
    setError(null);
    let sample: unknown;
    try {
      sample = JSON.parse(text);
    } catch (e) {
      setError(`Not valid JSON: ${(e as Error).message}`);
      return;
    }
    setBusy(true);
    try {
      await props.onInferred(sample);
      setText('');
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal opened={props.opened} onClose={props.onClose} title="Paste a JSON sample" size="lg">
      <Stack>
        <Text size="sm">
          Paste one example of the data. Field names and types are guessed from it; you can change everything before
          saving.
          {props.hasFields && ' The current fields will be replaced.'}
        </Text>
        <Textarea
          autosize
          minRows={8}
          maxRows={20}
          ff="monospace"
          value={text}
          onChange={(e) => setText(e.currentTarget.value)}
          placeholder={'{\n  "firstName": "Mari",\n  "birthDate": "1990-02-01",\n  "addresses": [{ "city": "Tallinn" }]\n}'}
          aria-label="JSON sample"
          data-autofocus
        />
        {error && (
          <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
            {error}
          </Alert>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={props.onClose}>
            Cancel
          </Button>
          <Button onClick={use} disabled={!text.trim()} loading={busy}>
            Use this sample
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
