import type { ReactNode } from 'react';
import { AuthContext } from './AuthContext.tsx';

const mockUser = {
  sub: 'local-dev',
  email: 'dev@localhost',
  name: 'Local Dev',
  picture: '',
};

/** Immediately authenticated with a mock user. For local development only. */
export function LocalAuthProvider({ children }: { children: ReactNode }) {
  return (
    <AuthContext
      value={{
        state: {
          status: 'authenticated',
          token: 'local-dev-token',
          user: mockUser,
        },
        handleUnauthorized: () => undefined,
        signIn: () => undefined,
      }}
    >
      {children}
    </AuthContext>
  );
}
