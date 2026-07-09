import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import { Box, Button, Group, SimpleGrid, Stack, Tabs, Text, Title } from '@mantine/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
import heroImg from './assets/hero.png';
import reactLogo from './assets/react.svg';
import viteLogo from './assets/vite.svg';
import { WorkloadService } from './gen/medusa/workload/v1/workload_service_pb.ts';
import { ProfilesPage } from './ProfilesPage.tsx';
import { SignInWall } from './SignInWall.tsx';
import { useAuth } from './useAuth.tsx';
import { WorkersPage } from './WorkersPage.tsx';
import classes from './App.module.css';

const API_URL = import.meta.env.VITE_API_URL as string;

if (!API_URL) {
  throw new Error('VITE_API_URL is not set');
}

const transport = createGrpcWebTransport({
  baseUrl: API_URL,
});

const client = createClient(WorkloadService, transport);

const socialLinks = [
  { label: 'GitHub', href: 'https://github.com/vitejs/vite', icon: 'github-icon' },
  { label: 'Discord', href: 'https://chat.vite.dev/', icon: 'discord-icon' },
  { label: 'X.com', href: 'https://x.com/vite_js', icon: 'x-icon' },
  { label: 'Bluesky', href: 'https://bsky.app/profile/vite.dev', icon: 'bluesky-icon' },
];

function AppContent({ token }: { token: string }) {
  const { handleUnauthorized } = useAuth();
  const [count, setCount] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);

  const headers = useMemo(() => ({ Authorization: `Bearer ${token}` }), [token]);

  const handleError = useCallback(
    (err: unknown) => {
      const message = err instanceof Error ? err.message : String(err);
      if (message.includes('401') || message.includes('unauthenticated')) {
        handleUnauthorized();
      } else {
        setError(message);
      }
    },
    [handleUnauthorized]
  );

  useEffect(() => {
    let cancelled = false;

    async function load() {
      try {
        const response = await client.getCount({}, { headers });
        if (!cancelled) {
          setCount(response.count);
        }
      } catch (err: unknown) {
        if (!cancelled) {
          handleError(err);
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [headers, handleError]);

  async function increment() {
    try {
      const response = await client.increment({}, { headers });
      setCount(response.count);
      setError(null);
    } catch (err: unknown) {
      handleError(err);
    }
  }

  async function decrement() {
    try {
      const response = await client.decrement({}, { headers });
      setCount(response.count);
      setError(null);
    } catch (err: unknown) {
      handleError(err);
    }
  }

  return (
    <>
      <Box className={classes.center}>
        <div className={classes.hero}>
          <img src={heroImg} className={classes.base} width="170" height="179" alt="" />
          <img src={reactLogo} className={classes.framework} alt="React logo" />
          <img src={viteLogo} className={classes.vite} alt="Vite logo" />
        </div>
        <Stack align="center" gap="md">
          <Title order={1} className={classes.count}>
            {count ?? '…'}
          </Title>
          <Group justify="center" gap="xs">
            <Button
              variant="light"
              size="md"
              aria-label="Decrement"
              onClick={() => void decrement()}
            >
              −
            </Button>
            <Button
              variant="light"
              size="md"
              aria-label="Increment"
              onClick={() => void increment()}
            >
              +
            </Button>
          </Group>
          {error !== null && (
            <Text c="red" size="sm">
              Failed to reach the API: {error}
            </Text>
          )}
        </Stack>
      </Box>

      <SimpleGrid cols={{ base: 1, sm: 2 }} spacing={0} className={classes.nextSteps}>
        <Box className={classes.section}>
          <svg className={classes.sectionIcon} role="presentation" aria-hidden="true">
            <use href="/icons.svg#documentation-icon" />
          </svg>
          <Title order={2} mb={4}>
            Documentation
          </Title>
          <Text c="dimmed">Your questions, answered</Text>
          <Group gap="xs" mt="md">
            <Button
              component="a"
              href="https://vite.dev/"
              target="_blank"
              rel="noreferrer"
              variant="default"
              leftSection={<img className={classes.linkIcon} src={viteLogo} alt="" />}
            >
              Explore Vite
            </Button>
            <Button
              component="a"
              href="https://react.dev/"
              target="_blank"
              rel="noreferrer"
              variant="default"
              leftSection={<img className={classes.linkIcon} src={reactLogo} alt="" />}
            >
              Learn more
            </Button>
          </Group>
        </Box>

        <Box className={classes.section}>
          <svg className={classes.sectionIcon} role="presentation" aria-hidden="true">
            <use href="/icons.svg#social-icon" />
          </svg>
          <Title order={2} mb={4}>
            Connect with us
          </Title>
          <Text c="dimmed">Join the Vite community</Text>
          <Group gap="xs" mt="md">
            {socialLinks.map(({ label, href, icon }) => (
              <Button
                key={label}
                component="a"
                href={href}
                target="_blank"
                rel="noreferrer"
                variant="default"
                leftSection={
                  <svg className={classes.linkIcon} role="presentation" aria-hidden="true">
                    <use href={`/icons.svg#${icon}`} />
                  </svg>
                }
              >
                {label}
              </Button>
            ))}
          </Group>
        </Box>
      </SimpleGrid>
    </>
  );
}

function AuthenticatedApp({ token }: { token: string }) {
  const [tab, setTab] = useState<string | null>('workers');

  return (
    <Tabs value={tab} onChange={setTab}>
      <Tabs.List>
        <Tabs.Tab value="workers">Workers</Tabs.Tab>
        <Tabs.Tab value="profiles">Profiles</Tabs.Tab>
        <Tabs.Tab value="counter">Counter</Tabs.Tab>
      </Tabs.List>

      <Tabs.Panel value="workers">
        <WorkersPage token={token} />
      </Tabs.Panel>
      <Tabs.Panel value="profiles">
        <ProfilesPage token={token} />
      </Tabs.Panel>
      <Tabs.Panel value="counter">
        <AppContent token={token} />
      </Tabs.Panel>
    </Tabs>
  );
}

function App() {
  const { state } = useAuth();

  if (state.status === 'loading') {
    return null;
  }
  if (state.status === 'unauthenticated') {
    return <SignInWall />;
  }
  return <AuthenticatedApp token={state.token} />;
}

export default App;
