import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Group,
  Loader,
  Modal,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
  validateEnvVarName,
  validateProfileId,
  validateSecretResourceName,
  validateServiceAccount,
} from './fleetValidation.ts';
import {
  FleetService,
  VerificationStatus,
  type Profile,
  type ProfileRevision,
  type Worker,
} from './gen/medusa/workload/v1/fleet_service_pb.ts';
import { useAuth } from './useAuth.tsx';

type EnvRow = { name: string; value: string };

function recordToRows(record: Record<string, string>): EnvRow[] {
  return Object.entries(record).map(([name, value]) => ({ name, value }));
}

function rowsToRecord(rows: EnvRow[]): Record<string, string> {
  const record: Record<string, string> = {};
  for (const row of rows) {
    if (row.name.trim() !== '') {
      record[row.name.trim()] = row.value;
    }
  }
  return record;
}

/** Blank-named rows are ignored (not yet filled in); everything else must be valid and unique. */
function envVarRowsAreValid(envVars: EnvRow[], secretEnvVars: EnvRow[]): boolean {
  const namesSeen = new Set<string>();
  for (const row of envVars) {
    const name = row.name.trim();
    if (name === '') {
      continue;
    }
    if (validateEnvVarName(name) !== null || namesSeen.has(name)) {
      return false;
    }
    namesSeen.add(name);
  }
  for (const row of secretEnvVars) {
    const name = row.name.trim();
    if (name === '') {
      continue;
    }
    if (
      validateEnvVarName(name) !== null ||
      validateSecretResourceName(row.value.trim()) !== null ||
      namesSeen.has(name)
    ) {
      return false;
    }
    namesSeen.add(name);
  }
  return true;
}

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

function VerificationBadge({ status }: { status: VerificationStatus }) {
  if (status === VerificationStatus.VERIFIED) {
    return (
      <Badge color="green" variant="light" miw={90}>
        Verified
      </Badge>
    );
  }
  if (status === VerificationStatus.BINDING_MISSING) {
    return (
      <Tooltip
        multiline
        w={280}
        label="The broker can't impersonate this service account. Has the owning project applied the workload-impersonation Terraform module?"
      >
        <Badge color="red" variant="light" miw={90}>
          Binding missing
        </Badge>
      </Tooltip>
    );
  }
  if (status === VerificationStatus.SECRET_INACCESSIBLE) {
    return (
      <Tooltip
        multiline
        w={280}
        label="The target service account can't read one or more referenced secrets. Has it been granted secretAccessor on each secret_env_vars reference?"
      >
        <Badge color="red" variant="light" miw={90}>
          Secret inaccessible
        </Badge>
      </Tooltip>
    );
  }
  return (
    <Badge color="gray" variant="light" miw={90}>
      Unverified
    </Badge>
  );
}

/**
 * Name/value rows for either env_vars (plain) or secret_env_vars (Secret Manager resource
 * references — never a value; the console never reads secret values, only resource names).
 */
function EnvVarRowsEditor({
  label,
  rows,
  onChange,
  valueLabel,
  valuePlaceholder,
  validateName,
  validateValue,
  secretInaccessible,
}: {
  label: string;
  rows: EnvRow[];
  onChange: (rows: EnvRow[]) => void;
  valueLabel: string;
  valuePlaceholder: string;
  validateName: (name: string) => string | null;
  validateValue?: (value: string) => string | null;
  secretInaccessible?: boolean;
}) {
  function update(index: number, patch: Partial<EnvRow>) {
    onChange(rows.map((row, i) => (i === index ? { ...row, ...patch } : row)));
  }

  function remove(index: number) {
    onChange(rows.filter((_, i) => i !== index));
  }

  return (
    <Stack gap="xs">
      <Text size="sm" fw={500}>
        {label}
      </Text>
      {rows.length === 0 && (
        <Text size="sm" c="dimmed">
          None
        </Text>
      )}
      {rows.map((row, index) => {
        const nameError = row.name.trim() !== '' ? validateName(row.name.trim()) : null;
        const valueError =
          validateValue && row.value.trim() !== '' ? validateValue(row.value.trim()) : null;
        return (
          <Group key={index} gap="xs" align="flex-start" wrap="nowrap">
            <TextInput
              placeholder="NAME"
              value={row.name}
              onChange={(e) => update(index, { name: e.currentTarget.value })}
              error={nameError}
              style={{ flex: 1 }}
            />
            <TextInput
              placeholder={valuePlaceholder}
              aria-label={valueLabel}
              value={row.value}
              onChange={(e) => update(index, { value: e.currentTarget.value })}
              error={valueError}
              style={{ flex: 2 }}
            />
            <ActionIcon
              variant="subtle"
              color="red"
              aria-label={`Remove ${row.name || 'row'}`}
              onClick={() => remove(index)}
              mt={4}
            >
              ✕
            </ActionIcon>
          </Group>
        );
      })}
      {secretInaccessible && rows.length > 0 && (
        <Alert color="red" variant="light">
          The target service account can't read one or more of these secrets. Grant it
          roles/secretmanager.secretAccessor via the workload-impersonation module's secret_ids
          input, then re-verify.
        </Alert>
      )}
      <Button
        variant="default"
        size="xs"
        onClick={() => onChange([...rows, { name: '', value: '' }])}
      >
        Add {label.toLowerCase()}
      </Button>
    </Stack>
  );
}

/** Added / removed / changed names between two revisions' env maps — never values for secrets. */
function EnvDiff({ previous, current }: { previous: ProfileRevision; current: ProfileRevision }) {
  const added: string[] = [];
  const removed: string[] = [];
  const changed: string[] = [];

  function diffMaps(prevMap: Record<string, string>, currMap: Record<string, string>) {
    for (const name of Object.keys(currMap)) {
      if (!(name in prevMap)) {
        added.push(name);
      } else if (prevMap[name] !== currMap[name]) {
        changed.push(name);
      }
    }
    for (const name of Object.keys(prevMap)) {
      if (!(name in currMap)) {
        removed.push(name);
      }
    }
  }

  diffMaps(previous.envVars, current.envVars);
  diffMaps(previous.secretEnvVars, current.secretEnvVars);

  if (added.length === 0 && removed.length === 0 && changed.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        No env changes
      </Text>
    );
  }

  return (
    <Group gap={4}>
      {added.sort().map((name) => (
        <Badge key={`added-${name}`} color="green" variant="light" size="sm">
          +{name}
        </Badge>
      ))}
      {changed.sort().map((name) => (
        <Badge key={`changed-${name}`} color="yellow" variant="light" size="sm">
          ~{name}
        </Badge>
      ))}
      {removed.sort().map((name) => (
        <Badge key={`removed-${name}`} color="red" variant="light" size="sm">
          -{name}
        </Badge>
      ))}
    </Group>
  );
}

type EditState = {
  profileId: string;
  currentServiceAccount: string;
  currentEnvVars: Record<string, string>;
  currentSecretEnvVars: Record<string, string>;
  currentVerificationStatus: VerificationStatus;
} | null;

export function ProfilesPage({ token }: { token: string }) {
  const { handleUnauthorized } = useAuth();
  const [profiles, setProfiles] = useState<Profile[] | null>(null);
  const [latestRevisions, setLatestRevisions] = useState<Map<string, ProfileRevision>>(new Map());
  const [workers, setWorkers] = useState<Worker[]>([]);
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

  const refresh = useCallback(async () => {
    try {
      const [profilesResponse, workersResponse] = await Promise.all([
        client.listProfiles({}, { headers }),
        client.listWorkers({}, { headers }),
      ]);
      const revisionEntries = await Promise.all(
        profilesResponse.profiles.map(async (profile) => {
          const revisionsResponse = await client.listProfileRevisions(
            { profileId: profile.profileId },
            { headers }
          );
          const latest =
            revisionsResponse.revisions.find((r) => r.revision === profile.latestRevision) ??
            revisionsResponse.revisions.at(-1);
          return [profile.profileId, latest] as const;
        })
      );
      setLatestRevisions(
        new Map(
          revisionEntries.filter(
            (entry): entry is [string, ProfileRevision] => entry[1] !== undefined
          )
        )
      );
      setProfiles(profilesResponse.profiles);
      setWorkers(workersResponse.workers);
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

  const [createOpened, setCreateOpened] = useState(false);
  const [editState, setEditState] = useState<EditState>(null);
  const [archiveTarget, setArchiveTarget] = useState<Profile | null>(null);
  const [detailsTarget, setDetailsTarget] = useState<Profile | null>(null);
  const [detailsRevisions, setDetailsRevisions] = useState<ProfileRevision[] | null>(null);

  async function openDetails(profile: Profile) {
    setDetailsTarget(profile);
    setDetailsRevisions(null);
    try {
      const response = await client.listProfileRevisions(
        { profileId: profile.profileId },
        { headers }
      );
      setDetailsRevisions(response.revisions);
    } catch (err: unknown) {
      handleError(err);
    }
  }

  async function verifyProfile(profileId: string) {
    try {
      await client.verifyProfile({ profileId }, { headers });
      toast.success(`Re-verified ${profileId}`);
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  async function submitArchive(profile: Profile) {
    try {
      await client.archiveProfile({ profileId: profile.profileId }, { headers });
      toast.success(`Archived ${profile.profileId}`);
      setArchiveTarget(null);
      await refresh();
    } catch (err: unknown) {
      handleError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  const grantedWorkerNames = useCallback(
    (profileId: string) =>
      workers.filter((w) => w.grantedProfileIds.includes(profileId)).map((w) => w.name),
    [workers]
  );

  if (profiles === null && error === null) {
    return (
      <Group justify="center" p="xl">
        <Loader />
      </Group>
    );
  }

  return (
    <Stack gap="xl" p="md">
      <Group justify="space-between">
        <Title order={1}>Profiles</Title>
        <Button onClick={() => setCreateOpened(true)}>Create profile</Button>
      </Group>

      {error !== null && (
        <Alert color="red" title="Failed to reach the API">
          {error}
        </Alert>
      )}

      {(profiles ?? []).length === 0 ? (
        <Text c="dimmed">No profiles yet.</Text>
      ) : (
        <Table.ScrollContainer minWidth={1100}>
          <Table striped withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Profile ID</Table.Th>
                <Table.Th>Target service account</Table.Th>
                <Table.Th>Latest revision</Table.Th>
                <Table.Th>Verification</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(profiles ?? []).map((profile) => {
                const revision = latestRevisions.get(profile.profileId);
                return (
                  <Table.Tr key={profile.profileId}>
                    <Table.Td>{profile.profileId}</Table.Td>
                    <Table.Td>{revision?.targetServiceAccount ?? '—'}</Table.Td>
                    <Table.Td>{profile.latestRevision}</Table.Td>
                    <Table.Td>
                      <VerificationBadge
                        status={revision?.verificationStatus ?? VerificationStatus.UNVERIFIED}
                      />
                    </Table.Td>
                    <Table.Td>
                      {profile.archived ? (
                        <Badge color="gray" variant="outline" miw={80}>
                          Archived
                        </Badge>
                      ) : (
                        <Badge color="blue" variant="outline" miw={80}>
                          Active
                        </Badge>
                      )}
                    </Table.Td>
                    <Table.Td>
                      <Group gap="xs" wrap="nowrap">
                        <Button
                          size="xs"
                          variant="default"
                          onClick={() => void openDetails(profile)}
                        >
                          Details
                        </Button>
                        <Button
                          size="xs"
                          variant="default"
                          disabled={profile.archived}
                          onClick={() =>
                            setEditState({
                              profileId: profile.profileId,
                              currentServiceAccount: revision?.targetServiceAccount ?? '',
                              currentEnvVars: revision?.envVars ?? {},
                              currentSecretEnvVars: revision?.secretEnvVars ?? {},
                              currentVerificationStatus:
                                revision?.verificationStatus ?? VerificationStatus.UNVERIFIED,
                            })
                          }
                        >
                          Edit
                        </Button>
                        <Button
                          size="xs"
                          variant="default"
                          onClick={() => void verifyProfile(profile.profileId)}
                        >
                          Re-verify
                        </Button>
                        <Button
                          size="xs"
                          variant="outline"
                          color="red"
                          disabled={profile.archived}
                          onClick={() => setArchiveTarget(profile)}
                        >
                          Archive
                        </Button>
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}

      <CreateProfileModal
        opened={createOpened}
        onClose={() => setCreateOpened(false)}
        headers={headers}
        onCreated={async () => {
          setCreateOpened(false);
          await refresh();
        }}
        onError={handleError}
      />

      <EditProfileModal
        state={editState}
        onClose={() => setEditState(null)}
        headers={headers}
        onUpdated={async () => {
          setEditState(null);
          await refresh();
        }}
        onError={handleError}
      />

      <Modal
        opened={archiveTarget !== null}
        onClose={() => setArchiveTarget(null)}
        title="Archive profile"
      >
        {archiveTarget && (
          <Stack gap="md">
            <Text>
              Archive <strong>{archiveTarget.profileId}</strong>? It will no longer be grantable to
              new workers. Existing grants stay in place until revoked.
            </Text>
            <Group justify="flex-end">
              <Button variant="default" onClick={() => setArchiveTarget(null)}>
                Cancel
              </Button>
              <Button color="red" onClick={() => void submitArchive(archiveTarget)}>
                Archive
              </Button>
            </Group>
          </Stack>
        )}
      </Modal>

      <Modal
        opened={detailsTarget !== null}
        onClose={() => setDetailsTarget(null)}
        title={detailsTarget ? `Profile: ${detailsTarget.profileId}` : ''}
        size="lg"
      >
        {detailsTarget && (
          <Stack gap="lg">
            <Stack gap="xs">
              <Title order={4}>Revision history</Title>
              {detailsRevisions === null ? (
                <Loader size="sm" />
              ) : (
                <Table.ScrollContainer minWidth={800}>
                  <Table striped withTableBorder>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Rev</Table.Th>
                        <Table.Th>Target service account</Table.Th>
                        <Table.Th>Created by</Table.Th>
                        <Table.Th>Note</Table.Th>
                        <Table.Th>Created at</Table.Th>
                        <Table.Th>Verification</Table.Th>
                        <Table.Th>Env changes</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {detailsRevisions.map((revision, index) => (
                        <Table.Tr key={revision.revision}>
                          <Table.Td>{revision.revision}</Table.Td>
                          <Table.Td>{revision.targetServiceAccount}</Table.Td>
                          <Table.Td>{revision.createdBy}</Table.Td>
                          <Table.Td>{revision.note || '—'}</Table.Td>
                          <Table.Td>{formatDate(revision.createdAt)}</Table.Td>
                          <Table.Td>
                            <VerificationBadge status={revision.verificationStatus} />
                          </Table.Td>
                          <Table.Td>
                            {index === 0 ? (
                              <Text size="sm" c="dimmed">
                                Initial revision
                              </Text>
                            ) : (
                              <EnvDiff previous={detailsRevisions[index - 1]} current={revision} />
                            )}
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              )}
            </Stack>
            <Stack gap="xs">
              <Title order={4}>Granted workers</Title>
              {grantedWorkerNames(detailsTarget.profileId).length === 0 ? (
                <Text c="dimmed">None</Text>
              ) : (
                <Group gap={4}>
                  {grantedWorkerNames(detailsTarget.profileId).map((name) => (
                    <Badge key={name} variant="light">
                      {name}
                    </Badge>
                  ))}
                </Group>
              )}
            </Stack>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}

function CreateProfileModal({
  opened,
  onClose,
  headers,
  onCreated,
  onError,
}: {
  opened: boolean;
  onClose: () => void;
  headers: Record<string, string>;
  onCreated: () => Promise<void>;
  onError: (err: unknown) => void;
}) {
  const [profileId, setProfileId] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [targetServiceAccount, setTargetServiceAccount] = useState('');
  const [note, setNote] = useState('');
  const [profileIdError, setProfileIdError] = useState<string | null>(null);
  const [serviceAccountError, setServiceAccountError] = useState<string | null>(null);
  const [envVars, setEnvVars] = useState<EnvRow[]>([]);
  const [secretEnvVars, setSecretEnvVars] = useState<EnvRow[]>([]);

  function reset() {
    setProfileId('');
    setDisplayName('');
    setTargetServiceAccount('');
    setNote('');
    setProfileIdError(null);
    setServiceAccountError(null);
    setEnvVars([]);
    setSecretEnvVars([]);
  }

  async function submit() {
    const idError = validateProfileId(profileId);
    const saError = validateServiceAccount(targetServiceAccount);
    setProfileIdError(idError);
    setServiceAccountError(saError);
    if (idError || saError || !envVarRowsAreValid(envVars, secretEnvVars)) {
      return;
    }
    try {
      await client.createProfile(
        {
          profileId,
          displayName,
          targetServiceAccount,
          note,
          envVars: rowsToRecord(envVars),
          secretEnvVars: rowsToRecord(secretEnvVars),
        },
        { headers }
      );
      toast.success(`Created ${profileId}`);
      reset();
      await onCreated();
    } catch (err: unknown) {
      onError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <Modal
      opened={opened}
      onClose={() => {
        reset();
        onClose();
      }}
      title="Create profile"
      size="lg"
    >
      <Stack gap="md">
        <TextInput
          label="Profile ID"
          placeholder="my-profile-1"
          value={profileId}
          onChange={(e) => setProfileId(e.currentTarget.value)}
          error={profileIdError}
          required
        />
        <TextInput
          label="Display name"
          value={displayName}
          onChange={(e) => setDisplayName(e.currentTarget.value)}
        />
        <TextInput
          label="Target service account"
          placeholder="name@project.iam.gserviceaccount.com"
          value={targetServiceAccount}
          onChange={(e) => setTargetServiceAccount(e.currentTarget.value)}
          error={serviceAccountError}
          required
        />
        <Textarea label="Note" value={note} onChange={(e) => setNote(e.currentTarget.value)} />
        <EnvVarRowsEditor
          label="Env vars"
          rows={envVars}
          onChange={setEnvVars}
          valueLabel="Value"
          valuePlaceholder="value"
          validateName={validateEnvVarName}
        />
        <EnvVarRowsEditor
          label="Secret env vars"
          rows={secretEnvVars}
          onChange={setSecretEnvVars}
          valueLabel="Secret Manager resource name"
          valuePlaceholder="projects/p/secrets/name/versions/latest"
          validateName={validateEnvVarName}
          validateValue={validateSecretResourceName}
        />
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button onClick={() => void submit()}>Create</Button>
        </Group>
      </Stack>
    </Modal>
  );
}

function EditProfileModal({
  state,
  onClose,
  headers,
  onUpdated,
  onError,
}: {
  state: EditState;
  onClose: () => void;
  headers: Record<string, string>;
  onUpdated: () => Promise<void>;
  onError: (err: unknown) => void;
}) {
  const [targetServiceAccount, setTargetServiceAccount] = useState('');
  const [note, setNote] = useState('');
  const [serviceAccountError, setServiceAccountError] = useState<string | null>(null);
  const [envVars, setEnvVars] = useState<EnvRow[]>([]);
  const [secretEnvVars, setSecretEnvVars] = useState<EnvRow[]>([]);

  useEffect(() => {
    if (state) {
      setTargetServiceAccount(state.currentServiceAccount);
      setNote('');
      setServiceAccountError(null);
      setEnvVars(recordToRows(state.currentEnvVars));
      setSecretEnvVars(recordToRows(state.currentSecretEnvVars));
    }
  }, [state]);

  async function submit() {
    if (!state) {
      return;
    }
    const saError = validateServiceAccount(targetServiceAccount);
    setServiceAccountError(saError);
    if (saError || !envVarRowsAreValid(envVars, secretEnvVars)) {
      return;
    }
    try {
      await client.updateProfile(
        {
          profileId: state.profileId,
          targetServiceAccount,
          note,
          envVars: rowsToRecord(envVars),
          secretEnvVars: rowsToRecord(secretEnvVars),
        },
        { headers }
      );
      toast.success(`Updated ${state.profileId}`);
      await onUpdated();
    } catch (err: unknown) {
      onError(err);
      toast.error(err instanceof Error ? err.message : String(err));
    }
  }

  return (
    <Modal
      opened={state !== null}
      onClose={onClose}
      title={state ? `Edit ${state.profileId}` : ''}
      size="lg"
    >
      {state && (
        <Stack gap="md">
          <Text c="dimmed" size="sm">
            Saving creates revision N+1 — the existing history is preserved, not overwritten.
          </Text>
          <TextInput
            label="Target service account"
            value={targetServiceAccount}
            onChange={(e) => setTargetServiceAccount(e.currentTarget.value)}
            error={serviceAccountError}
            required
          />
          <Textarea
            label="Note"
            placeholder="Why this change?"
            value={note}
            onChange={(e) => setNote(e.currentTarget.value)}
          />
          <EnvVarRowsEditor
            label="Env vars"
            rows={envVars}
            onChange={setEnvVars}
            valueLabel="Value"
            valuePlaceholder="value"
            validateName={validateEnvVarName}
          />
          <EnvVarRowsEditor
            label="Secret env vars"
            rows={secretEnvVars}
            onChange={setSecretEnvVars}
            valueLabel="Secret Manager resource name"
            valuePlaceholder="projects/p/secrets/name/versions/latest"
            validateName={validateEnvVarName}
            validateValue={validateSecretResourceName}
            secretInaccessible={
              state.currentVerificationStatus === VerificationStatus.SECRET_INACCESSIBLE
            }
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              Cancel
            </Button>
            <Button onClick={() => void submit()}>Save</Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
