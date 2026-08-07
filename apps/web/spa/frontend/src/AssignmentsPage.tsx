import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import { Alert, Button, Group, Loader, Select, Stack, Table, Text, Title } from '@mantine/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
  FleetService,
  type Assignment,
  type Profile,
  type Worker,
} from './gen/medusa/workload/v1/fleet_service_pb.ts';
import { useAuth } from './useAuth.tsx';

const API_URL = import.meta.env.VITE_API_URL as string;
const POLL_INTERVAL_MS = 5000;

const transport = createGrpcWebTransport({ baseUrl: API_URL });
const client = createClient(FleetService, transport);

function formatDate(iso: string): string {
  if (!iso) {
    return '—';
  }
  return new Date(iso).toLocaleString();
}

/**
 * The assignments page: a first-class (worker, profile) placement record, distinct from a grant
 * (which only authorizes — see WorkersPage's "Manage grants"). Creating or deleting an assignment
 * here never touches grants. Polls ListAssignments on the same cadence as the other pages.
 */
export function AssignmentsPage({ token }: { token: string }) {
  const { handleUnauthorized } = useAuth();
  const [assignments, setAssignments] = useState<Assignment[] | null>(null);
  const [workers, setWorkers] = useState<Worker[]>([]);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selectedWorker, setSelectedWorker] = useState<string | null>(null);
  const [selectedProfile, setSelectedProfile] = useState<string | null>(null);

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

  const refresh = useCallback(async () => {
    try {
      const [assignmentsResponse, workersResponse, profilesResponse] = await Promise.all([
        client.listAssignments({ workerId: '', profileId: '' }, { headers }),
        client.listWorkers({}, { headers }),
        client.listProfiles({}, { headers }),
      ]);
      setAssignments(assignmentsResponse.assignments);
      setWorkers(workersResponse.workers);
      setProfiles(profilesResponse.profiles);
      setError(null);
    } catch (err: unknown) {
      handleError(err);
    }
  }, [headers, handleError]);

  useEffect(() => {
    void refresh();
    const interval = setInterval(() => void refresh(), POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [refresh]);

  async function submitCreate() {
    if (selectedWorker === null || selectedProfile === null) {
      return;
    }
    try {
      await client.createAssignment(
        { workerId: selectedWorker, profileId: selectedProfile },
        { headers }
      );
      toast.success(`Assigned ${selectedProfile} to ${selectedWorker}`);
      setSelectedWorker(null);
      setSelectedProfile(null);
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  async function submitDelete(assignmentId: string) {
    try {
      await client.deleteAssignment({ assignmentId }, { headers });
      toast.success('Deleted assignment');
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  const workerName = (workerId: string) =>
    workers.find((w) => w.workerId === workerId)?.name || workerId;

  if (assignments === null && error === null) {
    return (
      <Group justify="center" p="xl">
        <Loader />
      </Group>
    );
  }

  return (
    <Stack gap="xl" p="md">
      <Title order={1}>Assignments</Title>

      {error !== null && (
        <Alert color="red" title="Couldn't load assignments">
          {error}
        </Alert>
      )}

      <Group align="flex-end" gap="sm">
        <Select
          label="Worker"
          placeholder="Pick a worker"
          data={workers.map((w) => ({ value: w.workerId, label: w.name || w.workerId }))}
          value={selectedWorker}
          onChange={setSelectedWorker}
          searchable
        />
        <Select
          label="Profile"
          placeholder="Pick a profile"
          data={profiles
            .filter((p) => !p.archived)
            .map((p) => ({ value: p.profileId, label: p.profileId }))}
          value={selectedProfile}
          onChange={setSelectedProfile}
          searchable
        />
        <Button
          onClick={() => void submitCreate()}
          disabled={selectedWorker === null || selectedProfile === null}
        >
          Create assignment
        </Button>
      </Group>

      {assignments !== null && assignments.length === 0 ? (
        <Text c="dimmed">No assignments.</Text>
      ) : (
        <Table.ScrollContainer minWidth={700}>
          <Table striped highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Worker</Table.Th>
                <Table.Th>Profile</Table.Th>
                <Table.Th>Created by</Table.Th>
                <Table.Th>Created</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(assignments ?? []).map((assignment) => (
                <Table.Tr key={assignment.assignmentId}>
                  <Table.Td>{workerName(assignment.workerId)}</Table.Td>
                  <Table.Td>{assignment.profileId}</Table.Td>
                  <Table.Td>{assignment.createdBy || '—'}</Table.Td>
                  <Table.Td>{formatDate(assignment.createdAt)}</Table.Td>
                  <Table.Td>
                    <Button
                      variant="subtle"
                      color="red"
                      size="xs"
                      onClick={() => void submitDelete(assignment.assignmentId)}
                    >
                      Delete
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}
