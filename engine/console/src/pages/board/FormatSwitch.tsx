import { useEffect, useState } from 'react';
import { Group, SegmentedControl, Text, TextInput } from '@mantine/core';
import { api } from '../../api';
import type { Formats, IntegrationDraft } from '../../model';
import type { Tab } from '../../board';

/**
 * JSON or XML for the current tab (D-24): on the request tab how the body goes to the API, on the response tab what
 * callers get back. XML needs a root element name. The API's answer is read by its Content-Type either way.
 */
export function FormatSwitch(props: {
  draft: IntegrationDraft;
  tab: Tab;
  onChange: (d: IntegrationDraft) => void;
  onError: (e: string) => void;
}) {
  const { draft, tab } = props;
  const formats: Formats = draft.formats ?? {};
  const part = tab === 'request' ? 'request' : 'output';
  const rootKey = tab === 'request' ? 'requestRoot' : 'outputRoot';
  const format = formats[part] ?? 'json';
  const savedRoot = formats[rootKey] ?? '';
  const [root, setRoot] = useState(savedRoot);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setRoot(savedRoot);
    setError(null);
  }, [savedRoot, tab]);

  // A bad root name is shown at its field; anything else in the board's error box.
  const save = async (next: Formats, atRoot: boolean) => {
    try {
      props.onChange(await api.saveFormats(draft.id, next));
      setError(null);
    } catch (e) {
      if (atRoot) setError((e as Error).message);
      else props.onError((e as Error).message);
    }
  };

  const choose = (v: string) => {
    const fallback = tab === 'request' ? 'request' : (draft.output ?? 'response');
    save(
      {
        ...formats,
        [part]: v,
        [rootKey]: v === 'xml' ? root || fallback : formats[rootKey],
      },
      false,
    );
  };

  return (
    <Group gap="sm" align="center">
      <Text size="sm">{tab === 'request' ? 'Send to the API as' : 'Return to callers as'}</Text>
      <SegmentedControl
        size="xs"
        value={format}
        onChange={choose}
        data={[
          { value: 'json', label: 'JSON' },
          { value: 'xml', label: 'XML' },
        ]}
      />
      {format === 'xml' && (
        <TextInput
          size="xs"
          aria-label="Root element"
          placeholder="Root element"
          leftSection={
            <Text size="xs" c="dimmed">
              {'<'}
            </Text>
          }
          rightSection={
            <Text size="xs" c="dimmed">
              {'>'}
            </Text>
          }
          value={root}
          error={error}
          onChange={(e) => setRoot(e.currentTarget.value)}
          onBlur={() => root !== savedRoot && save({ ...formats, [rootKey]: root.trim() }, true)}
          onKeyDown={(e) => e.key === 'Enter' && e.currentTarget.blur()}
          w={180}
        />
      )}
      {tab === 'response' && (
        <Text size="xs" c="dimmed">
          The API's answer is read as JSON or XML by its Content-Type.
        </Text>
      )}
    </Group>
  );
}
