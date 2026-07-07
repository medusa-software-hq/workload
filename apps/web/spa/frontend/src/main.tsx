import '@mantine/core/styles.css';
import './global.css';
import { MantineProvider } from '@mantine/core';
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Toaster } from 'sonner';
import App from './App.tsx';
import { AuthProvider } from './AuthProvider.tsx';
import { theme } from './theme.ts';

const root = document.getElementById('root');
if (!root) {
  throw new Error('Root element not found');
}

createRoot(root).render(
  <StrictMode>
    <MantineProvider theme={theme}>
      <AuthProvider>
        <App />
      </AuthProvider>
      <Toaster richColors position="bottom-right" />
    </MantineProvider>
  </StrictMode>
);
