import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  CloseButton,
  Group,
  Loader,
  Modal,
  Select,
  Stack,
  Table,
  Text,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
  FleetService,
  WorkerStatus,
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

type PendingAction = { kind: 'approve' | 'reject'; worker: Worker };
type ActiveAction = { kind: 'revoke'; worker: Worker };

export function WorkersPage({ token }: { token: string }) {
  const { handleUnauthorized } = useAuth();
  const [workers, setWorkers] = useState<Worker[] | null>(null);
  const [profiles, setProfiles] = useState<Profile[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [showResolved, setShowResolved] = useState(false);

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
      const [workersResponse, profilesResponse] = await Promise.all([
        client.listWorkers({}, { headers }),
        client.listProfiles({}, { headers }),
      ]);
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

  const pending = useMemo(
    () => (workers ?? []).filter((w) => w.status === WorkerStatus.PENDING),
    [workers]
  );
  const active = useMemo(
    () => (workers ?? []).filter((w) => w.status === WorkerStatus.ACTIVE),
    [workers]
  );
  const resolved = useMemo(
    () =>
      (workers ?? []).filter(
        (w) => w.status === WorkerStatus.REJECTED || w.status === WorkerStatus.REVOKED
      ),
    [workers]
  );

  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const [activeAction, setActiveAction] = useState<ActiveAction | null>(null);
  const [verified, { toggle: toggleVerified, close: resetVerified }] = useDisclosure(false);
  const [grantsWorkerId, setGrantsWorkerId] = useState<string | null>(null);
  const [profileToGrant, setProfileToGrant] = useState<string | null>(null);

  // Re-derived from the latest `workers` state on every render, so the modal reflects
  // grant/revoke changes immediately instead of showing a stale snapshot.
  const grantsWorker = workers?.find((w) => w.workerId === grantsWorkerId) ?? null;

  async function submitPendingAction(action: PendingAction) {
    try {
      if (action.kind === 'approve') {
        await client.approveWorker({ workerId: action.worker.workerId }, { headers });
        toast.success(`Approved ${action.worker.name}`);
      } else {
        await client.rejectWorker({ workerId: action.worker.workerId }, { headers });
        toast.success(`Rejected ${action.worker.name}`);
      }
      setPendingAction(null);
      resetVerified();
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  async function submitRevoke(action: ActiveAction) {
    try {
      await client.revokeWorker({ workerId: action.worker.workerId }, { headers });
      toast.success(`Revoked ${action.worker.name}`);
      setActiveAction(null);
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  async function submitGrant(workerId: string, profileId: string) {
    try {
      await client.grantProfile({ workerId, profileId }, { headers });
      toast.success(`Granted ${profileId}`);
      setProfileToGrant(null);
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  async function submitRevokeGrant(workerId: string, profileId: string) {
    try {
      await client.revokeProfileGrant({ workerId, profileId }, { headers });
      toast.success(`Revoked ${profileId}`);
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  if (workers === null && error === null) {
    return (
      <Group justify="center" p="xl">
        <Loader />
      </Group>
    );
  }

  return (
    <Stack gap="xl" p="md">
      <Title order={1}>Workers</Title>

      {error !== null && (
        <Alert color="red" title="Failed to reach the API">
          {error}
        </Alert>
      )}

      <Stack gap="sm">
        <Title order={2}>Pending approval</Title>

        {pending.length > 1 && (
          <Alert color="orange" title="Multiple pending registrations">
            {pending.length} workers are waiting for approval. Match each confirmation code with the
            requester out of band before approving — with more than one pending, the code is the
            only thing telling them apart.
          </Alert>
        )}

        {pending.length === 0 ? (
          <Text c="dimmed">No workers are pending approval.</Text>
        ) : (
          <Table striped withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Hostname</Table.Th>
                <Table.Th>OS</Table.Th>
                <Table.Th>Requested at</Table.Th>
                <Table.Th>Confirmation code</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {pending.map((worker) => (
                <Table.Tr key={worker.workerId}>
                  <Table.Td>{worker.name}</Table.Td>
                  <Table.Td>{worker.hostname || '—'}</Table.Td>
                  <Table.Td>{worker.os || '—'}</Table.Td>
                  <Table.Td>{formatDate(worker.createdAt)}</Table.Td>
                  <Table.Td>
                    <Text size="xl" fw={700} ff="monospace">
                      {worker.confirmationCode}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs">
                      <Button
                        size="xs"
                        onClick={() => setPendingAction({ kind: 'approve', worker })}
                      >
                        Approve
                      </Button>
                      <Button
                        size="xs"
                        variant="outline"
                        color="red"
                        onClick={() => setPendingAction({ kind: 'reject', worker })}
                      >
                        Reject
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>

      <Stack gap="sm">
        <Title order={2}>Active</Title>
        {active.length === 0 ? (
          <Text c="dimmed">No active workers.</Text>
        ) : (
          <Table striped withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Hostname</Table.Th>
                <Table.Th>Last seen</Table.Th>
                <Table.Th>Granted profiles</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {active.map((worker) => (
                <Table.Tr key={worker.workerId}>
                  <Table.Td>{worker.name}</Table.Td>
                  <Table.Td>{worker.hostname || '—'}</Table.Td>
                  <Table.Td>{worker.lastSeenAt ? formatDate(worker.lastSeenAt) : 'Never'}</Table.Td>
                  <Table.Td>
                    {worker.grantedProfileIds.length === 0 ? (
                      <Text c="dimmed">None</Text>
                    ) : (
                      <Group gap={4}>
                        {worker.grantedProfileIds.map((profileId) => (
                          <Badge key={profileId} variant="light">
                            {profileId}
                          </Badge>
                        ))}
                      </Group>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Group gap="xs">
                      <Button
                        size="xs"
                        variant="default"
                        onClick={() => setGrantsWorkerId(worker.workerId)}
                      >
                        Manage grants
                      </Button>
                      <Button
                        size="xs"
                        variant="outline"
                        color="red"
                        onClick={() => setActiveAction({ kind: 'revoke', worker })}
                      >
                        Revoke
                      </Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>

      <Stack gap="sm">
        <Button size="xs" variant="subtle" onClick={() => setShowResolved((prev) => !prev)}>
          {showResolved ? 'Hide' : 'Show'} rejected/revoked ({resolved.length})
        </Button>
        {showResolved && resolved.length > 0 && (
          <Table striped withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>Hostname</Table.Th>
                <Table.Th>Status</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {resolved.map((worker) => (
                <Table.Tr key={worker.workerId}>
                  <Table.Td>{worker.name}</Table.Td>
                  <Table.Td>{worker.hostname || '—'}</Table.Td>
                  <Table.Td>
                    {worker.status === WorkerStatus.REJECTED ? 'Rejected' : 'Revoked'}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>

      <Modal
        opened={pendingAction !== null}
        onClose={() => {
          setPendingAction(null);
          resetVerified();
        }}
        title={pendingAction?.kind === 'approve' ? 'Approve worker' : 'Reject worker'}
      >
        {pendingAction && (
          <Stack gap="md">
            {pendingAction.kind === 'approve' && pending.length > 1 && (
              <Alert color="orange">
                Multiple registrations are pending. Approving the wrong row grants Workload access
                to the wrong machine.
              </Alert>
            )}
            <Text>
              Does the requester for <strong>{pendingAction.worker.name}</strong> see code{' '}
              <Text component="span" fw={700} ff="monospace">
                {pendingAction.worker.confirmationCode}
              </Text>
              ?
            </Text>
            {pendingAction.kind === 'approve' && pending.length > 1 && (
              <Checkbox
                label="I've verified this is the correct requester"
                checked={verified}
                onChange={toggleVerified}
              />
            )}
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setPendingAction(null)}>
                Cancel
              </Button>
              <Button
                color={pendingAction.kind === 'approve' ? undefined : 'red'}
                disabled={pendingAction.kind === 'approve' && pending.length > 1 && !verified}
                onClick={() => void submitPendingAction(pendingAction)}
              >
                {pendingAction.kind === 'approve' ? 'Approve' : 'Reject'}
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>

      <Modal
        opened={activeAction !== null}
        onClose={() => setActiveAction(null)}
        title="Revoke worker"
      >
        {activeAction && (
          <Stack gap="md">
            <Text>
              Revoke access for <strong>{activeAction.worker.name}</strong>? This takes effect on
              their next request.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setActiveAction(null)}>
                Cancel
              </Button>
              <Button color="red" onClick={() => void submitRevoke(activeAction)}>
                Revoke
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>

      <Modal
        opened={grantsWorker !== null}
        onClose={() => {
          setGrantsWorkerId(null);
          setProfileToGrant(null);
        }}
        title={grantsWorker ? `Grants for ${grantsWorker.name}` : ''}
      >
        {grantsWorker && (
          <Stack gap="md">
            <Stack gap="xs">
              <Text fw={500} size="sm">
                Currently granted
              </Text>
              {grantsWorker.grantedProfileIds.length === 0 ? (
                <Text c="dimmed">None</Text>
              ) : (
                <Stack gap={4}>
                  {grantsWorker.grantedProfileIds.map((profileId) => (
                    <Group key={profileId} justify="space-between">
                      <Badge variant="light">{profileId}</Badge>
                      <CloseButton
                        aria-label={`Revoke ${profileId}`}
                        onClick={() => void submitRevokeGrant(grantsWorker.workerId, profileId)}
                      />
                    </Group>
                  ))}
                </Stack>
              )}
            </Stack>
            <Group align="flex-end" gap="xs">
              <Select
                label="Grant a profile"
                placeholder="Pick a profile"
                flex={1}
                data={profiles
                  .filter(
                    (p) => !p.archived && !grantsWorker.grantedProfileIds.includes(p.profileId)
                  )
                  .map((p) => p.profileId)}
                value={profileToGrant}
                onChange={setProfileToGrant}
              />
              <Button
                disabled={profileToGrant === null}
                onClick={() =>
                  profileToGrant !== null && void submitGrant(grantsWorker.workerId, profileToGrant)
                }
              >
                Grant
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}
