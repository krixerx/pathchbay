import { Alert, Anchor, Stack, Text, Title } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { Link, isRouteErrorResponse, useRouteError } from 'react-router-dom';
import { meaning } from '../theme';

/** An address the console has no page for (the catch-all route). */
export function NotFoundPage() {
  return (
    <Stack gap="sm" align="flex-start">
      <Title order={2}>Page not found</Title>
      <Text>There is no page at this address. It may have been renamed or deleted.</Text>
      <Anchor component={Link} to="/">
        Go to Integrations
      </Anchor>
    </Stack>
  );
}

/**
 * A page that failed while rendering or loading (the router's errorElement), so the router's own developer page is
 * never shown. Rendered outside the layout, so it carries its own way back.
 */
export function RouteErrorPage() {
  const error = useRouteError();
  const notFound = isRouteErrorResponse(error) && error.status === 404;
  const detail = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : error instanceof Error ? error.message : null;
  return (
    <Stack gap="sm" align="flex-start" p="lg">
      <Title order={2}>{notFound ? 'Page not found' : 'Something went wrong'}</Title>
      <Text>{notFound ? 'There is no page at this address.' : 'This page could not be shown. Reloading may help.'}</Text>
      {detail && !notFound && (
        <Text size="sm" c="dimmed">
          {detail}
        </Text>
      )}
      <Anchor href="#/">Go to Integrations</Anchor>
    </Stack>
  );
}

/** A page whose item could not be loaded (deleted, mistyped address, Patchbay unreachable), with the way back. */
export function LoadError(props: { text: string; back: { to: string; label: string } }) {
  return (
    <Stack gap="sm" align="flex-start">
      <Alert color={meaning.broken} icon={<IconAlertTriangle />} role="alert" w="100%" maw={720}>
        {props.text}
      </Alert>
      <Anchor component={Link} to={props.back.to}>
        {props.back.label}
      </Anchor>
    </Stack>
  );
}
