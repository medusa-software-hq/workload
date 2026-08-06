import { Tabs } from '@mantine/core';
import { useState } from 'react';
import { AssignmentsPage } from './AssignmentsPage.tsx';
import { ProfilesPage } from './ProfilesPage.tsx';
import { RunsPage } from './RunsPage.tsx';
import { SignInWall } from './SignInWall.tsx';
import { useAuth } from './useAuth.tsx';
import { WorkersPage } from './WorkersPage.tsx';

function AuthenticatedApp({ token }: { token: string }) {
  const [tab, setTab] = useState<string | null>('workers');

  return (
    <Tabs value={tab} onChange={setTab}>
      <Tabs.List>
        <Tabs.Tab value="workers">Workers</Tabs.Tab>
        <Tabs.Tab value="profiles">Profiles</Tabs.Tab>
        <Tabs.Tab value="assignments">Assignments</Tabs.Tab>
        <Tabs.Tab value="runs">Runs</Tabs.Tab>
      </Tabs.List>

      <Tabs.Panel value="workers">
        <WorkersPage token={token} />
      </Tabs.Panel>
      <Tabs.Panel value="profiles">
        <ProfilesPage token={token} />
      </Tabs.Panel>
      <Tabs.Panel value="assignments">
        <AssignmentsPage token={token} />
      </Tabs.Panel>
      <Tabs.Panel value="runs">
        <RunsPage token={token} />
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
