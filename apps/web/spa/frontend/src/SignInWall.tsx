import { Center, Stack, Text, Title } from '@mantine/core';
import { useEffect, useRef } from 'react';
import { useAuth } from './useAuth.tsx';

const CLIENT_ID = import.meta.env.VITE_GOOGLE_CLIENT_ID as string;

export function SignInWall() {
  const { signIn } = useAuth();
  const buttonRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (buttonRef.current) {
      google.accounts.id.renderButton(buttonRef.current, {
        type: 'standard',
        theme: 'outline',
        size: 'large',
        text: 'signin_with',
        logo_alignment: 'left',
      });
    }
  }, []);

  // The rendered button handles the click itself; signIn() is a fallback.
  void signIn;
  void CLIENT_ID;

  return (
    <Center mih="100svh">
      <Stack align="center" gap="md">
        <Title order={1}>Sign in</Title>
        <Text c="dimmed">Use your company account to continue.</Text>
        <div ref={buttonRef} />
      </Stack>
    </Center>
  );
}
