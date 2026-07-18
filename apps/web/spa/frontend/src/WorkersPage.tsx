import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import {
  Alert,
  Badge,
  Button,
  Checkbox,
  CloseButton,
  Code,
  CopyButton,
  Group,
  Loader,
  Modal,
  NumberInput,
  Select,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
  type EnrollmentToken,
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
  const [enrollmentTokens, setEnrollmentTokens] = useState<EnrollmentToken[]>([]);
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
      const [workersResponse, profilesResponse, tokensResponse] = await Promise.all([
        client.listWorkers({}, { headers }),
        client.listProfiles({}, { headers }),
        client.listEnrollmentTokens({}, { headers }),
      ]);
      setWorkers(workersResponse.workers);
      setProfiles(profilesResponse.profiles);
      setEnrollmentTokens(tokensResponse.enrollmentTokens);
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

  // "New worker" enrollment-token flow. `mintedToken` holds the just-created plaintext token; it is
  // shown exactly once in the dialog and cleared when the dialog closes — the token is never fetched
  // or displayed again (only its metadata is, in the outstanding list).
  const [newWorkerOpen, { open: openNewWorker, close: closeNewWorker }] = useDisclosure(false);
  const [newTokenNote, setNewTokenNote] = useState('');
  const [newTokenExpiryDays, setNewTokenExpiryDays] = useState<number>(7);
  const [newTokenRequireApproval, setNewTokenRequireApproval] = useState(false);
  const [mintedToken, setMintedToken] = useState<string | null>(null);
  const [creatingToken, setCreatingToken] = useState(false);
  const [tokenToRevoke, setTokenToRevoke] = useState<EnrollmentToken | null>(null);

  function resetNewWorkerForm() {
    setNewTokenNote('');
    setNewTokenExpiryDays(7);
    setNewTokenRequireApproval(false);
    setMintedToken(null);
    setCreatingToken(false);
  }

  function dismissNewWorker() {
    closeNewWorker();
    resetNewWorkerForm();
  }

  async function submitCreateToken() {
    setCreatingToken(true);
    try {
      const response = await client.createEnrollmentToken(
        {
          note: newTokenNote.trim(),
          expiresInDays: newTokenExpiryDays,
          requireApproval: newTokenRequireApproval,
        },
        { headers }
      );
      setMintedToken(response.token);
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    } finally {
      setCreatingToken(false);
    }
  }

  async function submitRevokeToken(tokenId: string) {
    try {
      await client.revokeEnrollmentToken({ enrollmentTokenId: tokenId }, { headers });
      toast.success('Enrollment token revoked');
      setTokenToRevoke(null);
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

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
        <Group justify="space-between" align="center">
          <Title order={2}>Enrollment tokens</Title>
          <Button onClick={openNewWorker}>New worker</Button>
        </Group>
        <Text c="dimmed" size="sm">
          Mint a one-time token and hand it to a teammate. They register with it once, and it's
          burnt — no open registration, no confirmation code.
        </Text>

        {enrollmentTokens.length === 0 ? (
          <Text c="dimmed">No outstanding enrollment tokens.</Text>
        ) : (
          <Table striped withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Note</Table.Th>
                <Table.Th>Created by</Table.Th>
                <Table.Th>Expires</Table.Th>
                <Table.Th>Approval</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {enrollmentTokens.map((enrollmentToken) => (
                <Table.Tr key={enrollmentToken.enrollmentTokenId}>
                  <Table.Td>{enrollmentToken.note || '—'}</Table.Td>
                  <Table.Td>{enrollmentToken.createdBy}</Table.Td>
                  <Table.Td>{formatDate(enrollmentToken.expiresAt)}</Table.Td>
                  <Table.Td>
                    {enrollmentToken.requireApproval ? (
                      <Badge variant="light" color="orange">
                        Required
                      </Badge>
                    ) : (
                      <Text c="dimmed" size="sm">
                        Auto
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="outline"
                      color="red"
                      onClick={() => setTokenToRevoke(enrollmentToken)}
                    >
                      Revoke
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Stack>

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
        opened={newWorkerOpen}
        onClose={dismissNewWorker}
        title={mintedToken === null ? 'New worker' : 'Enrollment token created'}
        closeOnClickOutside={mintedToken === null}
      >
        {mintedToken === null ? (
          <Stack gap="md">
            <Text size="sm">
              Generates a one-time enrollment token to hand to the teammate setting up this worker.
            </Text>
            <TextInput
              label="Note"
              placeholder="e.g. for Kuba's MBP"
              value={newTokenNote}
              onChange={(event) => setNewTokenNote(event.currentTarget.value)}
            />
            <NumberInput
              label="Expires in (days)"
              min={1}
              max={365}
              value={newTokenExpiryDays}
              onChange={(value) => setNewTokenExpiryDays(typeof value === 'number' ? value : 7)}
            />
            <Checkbox
              label="Require admin approval after registration"
              checked={newTokenRequireApproval}
              onChange={(event) => setNewTokenRequireApproval(event.currentTarget.checked)}
            />
            <Group justify="flex-end">
              <Button variant="default" onClick={dismissNewWorker}>
                Cancel
              </Button>
              <Button loading={creatingToken} onClick={() => void submitCreateToken()}>
                Create token
              </Button>
            </Group>
          </Stack>
        ) : (
          <Stack gap="md">
            <Alert color="orange" title="Copy this token now">
              This is the only time the token is shown. It won't be displayed again — if you lose
              it, revoke it and create a new one.
            </Alert>
            <Code block data-testid="minted-token">
              {mintedToken}
            </Code>
            <Group justify="flex-end">
              <CopyButton value={mintedToken}>
                {({ copied, copy }) => (
                  <Button variant="light" onClick={copy}>
                    {copied ? 'Copied' : 'Copy token'}
                  </Button>
                )}
              </CopyButton>
              <Button onClick={dismissNewWorker}>Done</Button>
            </Group>
          </Stack>
        )}
      </Modal>

      <Modal
        opened={tokenToRevoke !== null}
        onClose={() => setTokenToRevoke(null)}
        title="Revoke enrollment token"
      >
        {tokenToRevoke && (
          <Stack gap="md">
            <Text>
              Revoke the enrollment token
              {tokenToRevoke.note ? (
                <>
                  {' '}
                  <strong>{tokenToRevoke.note}</strong>
                </>
              ) : (
                ''
              )}
              ? It can no longer be redeemed, and any teammate still holding it will have to be
              given a new one.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setTokenToRevoke(null)}>
                Cancel
              </Button>
              <Button
                color="red"
                onClick={() => void submitRevokeToken(tokenToRevoke.enrollmentTokenId)}
              >
                Revoke
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
