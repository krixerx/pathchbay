import { useEffect, useState } from 'react';
import { Alert, Button, Code, Group, Skeleton, Stack, Stepper, Text, Title } from '@mantine/core';
import { IconAlertTriangle, IconSettings } from '@tabler/icons-react';
import { Link, useParams } from 'react-router-dom';
import { api } from '../api';
import type { IntegrationDraft } from '../model';
import { meaning } from '../theme';
import { PayloadStep } from './steps/PayloadStep';
import { ImportStep } from './steps/ImportStep';
import { OperationStep } from './steps/OperationStep';
import { MapStep } from './steps/MapStep';
import { PublishStep } from './steps/PublishStep';
import type { Tab } from '../board';

/** One integration: the 5 steps (D-2, D-19). Finished steps can be reopened at any time (D-19). */
export function IntegrationPage() {
  const { slug = '' } = useParams();
  return <Integration key={slug} slug={slug} />;
}

function Integration({ slug }: { slug: string }) {
  const [draft, setDraft] = useState<IntegrationDraft | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [active, setActive] = useState(0);
  // The contract chosen in step 2; saved into the draft together with the operation in step 3.
  const [contractId, setContractId] = useState<string | null>(null);
  // The board tab to open when step 5 sends the user to a problem ("Show").
  const [mapTab, setMapTab] = useState<Tab>('request');

  useEffect(() => {
    api.integration(slug).then(
      (d) => {
        setDraft(d);
        setContractId(d.contract ?? null);
        setActive(!d.input || !d.output ? 0 : !d.contract ? 1 : !d.operationId ? 2 : 3);
      },
      (e: Error) => setError(e.message),
    );
  }, [slug]);

  if (!draft) {
    return error ? (
      <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert">
        {error}
      </Alert>
    ) : (
      <Skeleton height={200} />
    );
  }

  const linkCount = (draft.request?.links?.length ?? 0) + (draft.response?.links?.length ?? 0);
  // Steps 4 and 5 need the operation from step 3.
  const reachable = (step: number) => step <= 2 || !!draft.operationId;

  return (
    <Stack gap="lg" maw={1200}>
      <Group justify="space-between" align="flex-start">
        <div>
          <Title order={2}>{draft.name ?? draft.id}</Title>
          <Text size="sm" c="dimmed">
            <Code>/api/v1/integrations/{draft.id}</Code>
            {draft.activeVersion ? ` · live version ${draft.activeVersion}` : ' · not published yet'}
            {draft.target?.method && (
              <>
                {' · calls '}
                <Code>
                  {draft.target.method} {draft.target.baseUrl ?? '(no base URL yet)'}
                  {draft.target.path}
                </Code>
              </>
            )}
          </Text>
        </div>
        <Button variant="default" leftSection={<IconSettings size={16} />} component={Link} to={`/integrations/${slug}/settings`}>
          Settings
        </Button>
      </Group>

      <Stepper active={active} onStepClick={(s) => reachable(s) && setActive(s)} size="sm">
        <Stepper.Step label="Payload" description="Input and Output">
          <PayloadStep draft={draft} onChange={setDraft} onNext={() => setActive(1)} />
        </Stepper.Step>
        <Stepper.Step label="Import" description="Swagger file">
          <ImportStep
            selected={contractId}
            onSelect={(id) => {
              setContractId(id);
              setActive(2);
            }}
          />
        </Stepper.Step>
        <Stepper.Step label="Operation" description="What to call">
          <OperationStep draft={draft} contractId={contractId} linkCount={linkCount} onChange={setDraft} onBack={() => setActive(1)} />
        </Stepper.Step>
        <Stepper.Step label="Map" description="Link fields" allowStepSelect={!!draft.operationId}>
          <MapStep draft={draft} onChange={setDraft} initialTab={mapTab} />
        </Stepper.Step>
        <Stepper.Step label="Publish" description="Make it live" allowStepSelect={!!draft.operationId}>
          <PublishStep
            draft={draft}
            onChange={setDraft}
            onShow={(tab) => {
              setMapTab(tab);
              setActive(3);
            }}
          />
        </Stepper.Step>
      </Stepper>
    </Stack>
  );
}
