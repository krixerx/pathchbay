import { AppShell, Group, NavLink, Text } from '@mantine/core';
import { Link, Outlet, createHashRouter, useLocation } from 'react-router-dom';
import { IntegrationsPage } from './pages/IntegrationsPage';
import { IntegrationPage } from './pages/IntegrationPage';
import { DefinitionsPage } from './pages/DefinitionsPage';
import { DefinitionEditorPage } from './pages/DefinitionEditorPage';
import { SettingsPage } from './pages/SettingsPage';

function Layout() {
  const { pathname } = useLocation();
  const inDefinitions = pathname.startsWith('/definitions');
  return (
    <AppShell header={{ height: 56 }} padding="lg">
      <AppShell.Header px="lg">
        <Group h="100%" gap="xl">
          <Text fw={600} size="lg">
            Integration Mapper
          </Text>
          <Group gap="xs" component="nav" aria-label="Main">
            <NavLink component={Link} to="/" label="Integrations" active={!inDefinitions} w="auto" />
            <NavLink component={Link} to="/definitions" label="Payload definitions" active={inDefinitions} w="auto" />
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}

// Hash routes, so the engine can serve the console as plain static files.
export const router = createHashRouter([
  {
    element: <Layout />,
    children: [
      { path: '/', element: <IntegrationsPage /> },
      { path: '/integrations/:slug', element: <IntegrationPage /> },
      { path: '/integrations/:slug/settings', element: <SettingsPage /> },
      { path: '/definitions', element: <DefinitionsPage /> },
      { path: '/definitions/new', element: <DefinitionEditorPage /> },
      { path: '/definitions/:id', element: <DefinitionEditorPage /> },
    ],
  },
]);
