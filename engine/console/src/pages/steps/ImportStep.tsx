import { useEffect, useState } from 'react';
import { Alert, Button, FileInput, Group, List, Select, Stack, Tabs, Text, TextInput } from '@mantine/core';
import { IconAlertTriangle, IconCheck, IconFileUpload, IconInfoCircle, IconLink } from '@tabler/icons-react';
import { api } from '../../api';
import type { Contract, ContractSummary } from '../../model';
import { meaning } from '../../theme';

/**
 * Step 2: the external system's contract (D-5). Upload a file, give a URL (fetched by the engine), or reuse a contract
 * that was imported before. Unresolved parts are warnings; the import still goes on.
 */
export function ImportStep(props: { selected: string | null; onSelect: (contractId: string) => void }) {
  const [contracts, setContracts] = useState<ContractSummary[]>([]);
  const [name, setName] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [url, setUrl] = useState('');
  const [mode, setMode] = useState<string | null>('file');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<Contract | null>(null);

  useEffect(() => {
    api.contracts().then(setContracts, (e: Error) => setError(e.message));
  }, [result]);

  const importNow = async () => {
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const source = mode === 'file' ? { content: await file!.text() } : { url };
      setResult(await api.importContract(name, source));
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  };

  const ready = name.trim() && (mode === 'file' ? !!file : url.trim().length > 0);

  return (
    <Stack gap="lg" mt="lg" maw={720}>
      {contracts.length > 0 && (
        <Stack gap="xs">
          <Select
            label="Use a contract you imported before"
            placeholder="Choose a contract"
            data={contracts.map((c) => ({ value: c.id, label: `${c.name} · ${c.operationCount} operations` }))}
            value={props.selected}
            onChange={(v) => v && props.onSelect(v)}
            searchable
          />
          <Text size="sm" c="dimmed">
            Or import a new one:
          </Text>
        </Stack>
      )}

      <TextInput
        label="Name of the external system"
        description="For example “Population register”. Importing again under the same name replaces the contract."
        value={name}
        onChange={(e) => setName(e.currentTarget.value)}
      />
      <Tabs value={mode} onChange={setMode}>
        <Tabs.List>
          <Tabs.Tab value="file" leftSection={<IconFileUpload size={16} />}>
            Upload a file
          </Tabs.Tab>
          <Tabs.Tab value="url" leftSection={<IconLink size={16} />}>
            From a URL
          </Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="file" pt="sm">
          <FileInput
            label="OpenAPI or Swagger file"
            description="JSON or YAML. Swagger 2.0 is converted."
            placeholder="Choose a file"
            accept=".json,.yaml,.yml,application/json"
            value={file}
            onChange={setFile}
            clearable
          />
        </Tabs.Panel>
        <Tabs.Panel value="url" pt="sm">
          <TextInput
            label="URL of the OpenAPI or Swagger file"
            description="The engine fetches it, so it must be reachable from where the engine runs."
            placeholder="https://example.org/swagger/v1/swagger.json"
            value={url}
            onChange={(e) => setUrl(e.currentTarget.value)}
          />
        </Tabs.Panel>
      </Tabs>
      <Group>
        <Button onClick={importNow} loading={busy} disabled={!ready}>
          Import
        </Button>
        {busy && (
          <Text size="sm" c="dimmed" aria-live="polite">
            Reading contract…
          </Text>
        )}
      </Group>

      {error && (
        <Alert color={meaning.broken} icon={<IconAlertTriangle />} title="Not imported" role="alert">
          {error}
        </Alert>
      )}

      {result && (
        <Stack gap="sm" aria-live="polite">
          <Alert color={meaning.accent} icon={<IconCheck />} title={`${result.operations.length} operations found`}>
            “{result.name}” is imported.
          </Alert>
          {result.warnings.length > 0 && (
            <Alert color={meaning.warning} icon={<IconInfoCircle />} title="Imported with warnings">
              <List size="sm">
                {result.warnings.map((w) => (
                  <List.Item key={w}>{w}</List.Item>
                ))}
              </List>
            </Alert>
          )}
          <Group>
            <Button onClick={() => props.onSelect(result.id)} disabled={result.operations.length === 0}>
              Next: pick the operation
            </Button>
          </Group>
        </Stack>
      )}
    </Stack>
  );
}
