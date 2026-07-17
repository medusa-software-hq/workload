import { Code, ConnectError, createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Divider,
  Group,
  Loader,
  Modal,
  Paper,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
  Tooltip,
} from '@mantine/core';
import { type ReactNode, useCallback, useEffect, useMemo, useState } from 'react';
import { toast } from 'sonner';
import {
  validateEnvVarName,
  validateImageRef,
  validateProfileId,
  validateSecretResourceName,
  validateServiceAccount,
} from './fleetValidation.ts';
import {
  FleetService,
  ImageStatus,
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

/** Status badge for an image whose digest didn't pin — mirrors [VerificationBadge]. */
function ImageBadge({ status }: { status: ImageStatus }) {
  if (status === ImageStatus.UNRESOLVABLE) {
    return (
      <Tooltip
        multiline
        w={280}
        label="The image tag couldn't be resolved to a digest — the tag/repo is missing, or the target service account lacks read access. Grant artifactregistry.reader via the workload-impersonation module's artifact_repository_id input, then re-verify."
      >
        <Badge color="red" variant="light">
          Unresolvable
        </Badge>
      </Tooltip>
    );
  }
  return (
    <Tooltip
      multiline
      w={280}
      label="The image digest couldn't be resolved yet (a transient error). Re-verify the profile to retry."
    >
      <Badge color="yellow" variant="light">
        Pending
      </Badge>
    </Tooltip>
  );
}

type ImagePreview =
  | { state: 'idle' }
  // A valid image ref is entered, but there's no target SA yet to resolve it against.
  | { state: 'awaiting-sa' }
  | { state: 'loading' }
  | { state: 'resolved'; digest: string }
  | { state: 'unresolved'; status: ImageStatus; detail: string };

/**
 * Live preview of what an image tag resolves to, for a given target SA — the read-only ResolveImage
 * call. The admin sees the exact digest *before* pinning it (and any unresolvable reason surfaces in
 * the form, not as a flag on an already-created revision). The resolved digest is fed back as
 * CreateProfile/UpdateProfile's expectedDockerImageDigest, so the backend pins exactly what was
 * shown here.
 */
function useImagePreview(
  dockerImage: string,
  targetServiceAccount: string,
  headers: Record<string, string>,
  // Bump to force a re-resolve without changing the ref — used after a CAS mismatch, when the tag
  // moved server-side but the inputs here didn't.
  refreshNonce: number
): ImagePreview {
  const [preview, setPreview] = useState<ImagePreview>({ state: 'idle' });

  useEffect(() => {
    const image = dockerImage.trim();
    const sa = targetServiceAccount.trim();
    // No ref yet (or it's malformed — the field shows its own error): nothing to preview.
    if (image === '' || validateImageRef(image) !== null) {
      setPreview({ state: 'idle' });
      return;
    }
    // The ref is good, but resolving a digest means pulling as the target SA (ResolveImage
    // impersonates it), so we can't preview until one is entered. Say so rather than sit silent —
    // otherwise a filled-in image with an empty SA looks like the preview is just broken.
    if (validateServiceAccount(sa) !== null) {
      setPreview({ state: 'awaiting-sa' });
      return;
    }

    let cancelled = false;
    setPreview({ state: 'loading' });
    // Debounce so a fast typist doesn't fire a resolve per character.
    const timer = setTimeout(() => {
      client
        .resolveImage({ dockerImage: image, targetServiceAccount: sa }, { headers })
        .then((res) => {
          if (cancelled) {
            return;
          }
          if (res.imageStatus === ImageStatus.RESOLVED && res.dockerImageDigest !== '') {
            setPreview({ state: 'resolved', digest: res.dockerImageDigest });
          } else {
            setPreview({ state: 'unresolved', status: res.imageStatus, detail: res.detail });
          }
        })
        .catch((err: unknown) => {
          if (cancelled) {
            return;
          }
          setPreview({
            state: 'unresolved',
            status: ImageStatus.UNDETERMINED,
            detail: err instanceof Error ? err.message : String(err),
          });
        });
    }, 400);

    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [dockerImage, targetServiceAccount, headers, refreshNonce]);

  return preview;
}

/** True when an error is the backend's CAS rejection (the tag moved since the preview). */
function isTagMovedError(err: unknown): boolean {
  return ConnectError.from(err).code === Code.FailedPrecondition;
}

/** Shows the resolved digest (or why it couldn't resolve) beneath the image input. */
function ImagePreviewLine({ preview }: { preview: ImagePreview }) {
  if (preview.state === 'idle') {
    return null;
  }
  if (preview.state === 'awaiting-sa') {
    return (
      <Text size="sm" c="dimmed">
        Enter the target service account above to preview the digest this tag pins to.
      </Text>
    );
  }
  if (preview.state === 'loading') {
    return (
      <Group gap={6}>
        <Loader size="xs" />
        <Text size="sm" c="dimmed">
          Resolving digest…
        </Text>
      </Group>
    );
  }
  if (preview.state === 'resolved') {
    // Prominent, labelled box (not a dim one-liner) — this is the exact digest that will be
    // pinned into the revision, so the admin should see it clearly before committing. Mirrors
    // the "pinned to …" line in the read-only revision view.
    return (
      <Paper withBorder p="xs" radius="sm" bg="var(--mantine-color-green-light)">
        <Text size="xs" c="dimmed" tt="uppercase" fw={600} mb={2}>
          Will be pinned to this exact image
        </Text>
        <Text size="sm" style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}>
          {preview.digest}
        </Text>
      </Paper>
    );
  }
  return (
    <Alert color={preview.status === ImageStatus.UNRESOLVABLE ? 'red' : 'yellow'} p="xs">
      <Text size="sm">
        Can't resolve this image
        {preview.detail !== '' ? `: ${preview.detail}` : '.'}
      </Text>
      {preview.status === ImageStatus.UNRESOLVABLE && (
        <Text size="xs" mt={4}>
          The target service account likely lacks roles/artifactregistry.reader on this repository —
          grant it via the workload-impersonation module's artifact_repository_id input.
        </Text>
      )}
    </Alert>
  );
}

/** The digest to send as the CAS token, or '' when nothing resolved. */
function previewDigest(preview: ImagePreview): string {
  return preview.state === 'resolved' ? preview.digest : '';
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

  // Image change: a different tag, or — the case that would otherwise hide — the *same* tag pinned
  // to a different digest (someone re-pushed the tag), which shows up as a digest change here.
  let imageChange: string | null = null;
  if (previous.dockerImage !== current.dockerImage) {
    imageChange = current.dockerImage === '' ? 'image removed' : 'image tag';
  } else if (
    current.dockerImage !== '' &&
    previous.dockerImageDigest !== current.dockerImageDigest
  ) {
    imageChange = 'image digest';
  }

  if (added.length === 0 && removed.length === 0 && changed.length === 0 && imageChange === null) {
    return (
      <Text size="sm" c="dimmed">
        No changes
      </Text>
    );
  }

  return (
    <Group gap={4}>
      {imageChange !== null && (
        <Badge color="blue" variant="light" size="sm">
          {imageChange}
        </Badge>
      )}
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

/** A labelled read-only field for the detail view — the display counterpart to a form input. */
function DetailField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Stack gap={2}>
      <Text size="xs" c="dimmed" fw={500} tt="uppercase">
        {label}
      </Text>
      {typeof children === 'string' ? <Text size="sm">{children}</Text> : children}
    </Stack>
  );
}

/** A read-only name→value list (env vars) / name→resource list (secret env vars). */
function ReadOnlyVars({
  vars,
  emptyLabel,
  mono,
}: {
  vars: Record<string, string>;
  emptyLabel: string;
  mono?: boolean;
}) {
  const entries = Object.entries(vars).sort(([a], [b]) => a.localeCompare(b));
  if (entries.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        {emptyLabel}
      </Text>
    );
  }
  return (
    <Table withRowBorders={false} verticalSpacing={2} horizontalSpacing="md">
      <Table.Tbody>
        {entries.map(([name, value]) => (
          <Table.Tr key={name}>
            <Table.Td style={{ width: '1%', whiteSpace: 'nowrap', verticalAlign: 'top' }}>
              <Text size="sm" fw={500}>
                {name}
              </Text>
            </Table.Td>
            <Table.Td>
              <Text
                size="sm"
                style={{ fontFamily: mono ? 'monospace' : undefined, wordBreak: 'break-all' }}
              >
                {value}
              </Text>
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

/** `‹ 3 › of 5` — steps through a profile's revisions, latest by default. */
function RevisionPager({
  revisions,
  index,
  onIndex,
}: {
  revisions: ProfileRevision[];
  index: number;
  onIndex: (i: number) => void;
}) {
  const current = revisions[index];
  const isLatest = index === revisions.length - 1;
  return (
    <Group gap="xs" align="center">
      <ActionIcon
        variant="default"
        aria-label="Previous revision"
        disabled={index === 0}
        onClick={() => onIndex(index - 1)}
      >
        ‹
      </ActionIcon>
      <Group gap={6} align="baseline">
        <Text size="xs" c="dimmed">
          Revision
        </Text>
        <Text
          fw={600}
          style={{ minWidth: 24, textAlign: 'center', fontVariantNumeric: 'tabular-nums' }}
        >
          {current.revision}
        </Text>
        <Text size="xs" c="dimmed">
          of {revisions.length}
        </Text>
      </Group>
      <ActionIcon
        variant="default"
        aria-label="Next revision"
        disabled={isLatest}
        onClick={() => onIndex(index + 1)}
      >
        ›
      </ActionIcon>
      {isLatest && (
        <Badge size="sm" variant="light" color="blue">
          Latest
        </Badge>
      )}
    </Group>
  );
}

/** Read-only rendering of one revision — the big layout that replaces the history table. */
function RevisionView({
  revision,
  previous,
}: {
  revision: ProfileRevision;
  previous: ProfileRevision | null;
}) {
  const hasImage =
    revision.imageStatus !== ImageStatus.NOT_APPLICABLE && revision.dockerImage !== '';
  return (
    <Stack gap="lg">
      <Group grow align="flex-start">
        <DetailField label="Target service account">
          <Text size="sm" style={{ wordBreak: 'break-all' }}>
            {revision.targetServiceAccount}
          </Text>
        </DetailField>
        <DetailField label="Verification">
          <Group gap={0}>
            <VerificationBadge status={revision.verificationStatus} />
          </Group>
        </DetailField>
      </Group>

      <Group grow align="flex-start">
        <DetailField label="Created">{formatDate(revision.createdAt)}</DetailField>
        <DetailField label="Created by">{revision.createdBy}</DetailField>
      </Group>

      <DetailField label="Note">{revision.note || '—'}</DetailField>

      <DetailField label="Container image">
        {hasImage ? (
          <Stack gap={4}>
            <Text size="sm" style={{ wordBreak: 'break-all' }}>
              {revision.dockerImage}
            </Text>
            {revision.imageStatus === ImageStatus.RESOLVED ? (
              <Text
                size="xs"
                c="dimmed"
                style={{ fontFamily: 'monospace', wordBreak: 'break-all' }}
              >
                pinned to {revision.dockerImageDigest}
              </Text>
            ) : (
              <Group gap={0}>
                <ImageBadge status={revision.imageStatus} />
              </Group>
            )}
          </Stack>
        ) : (
          <Text size="sm" c="dimmed">
            None — a pure exec/env profile.
          </Text>
        )}
      </DetailField>

      <DetailField label="Env vars">
        <ReadOnlyVars vars={revision.envVars} emptyLabel="None" />
      </DetailField>

      <DetailField label="Secret env vars">
        <ReadOnlyVars vars={revision.secretEnvVars} emptyLabel="None" mono />
      </DetailField>

      {previous !== null && (
        <DetailField label="Changes from the previous revision">
          <EnvDiff previous={previous} current={revision} />
        </DetailField>
      )}
    </Stack>
  );
}

type EditState = {
  profileId: string;
  currentServiceAccount: string;
  currentEnvVars: Record<string, string>;
  currentSecretEnvVars: Record<string, string>;
  currentDockerImage: string;
  currentVerificationStatus: VerificationStatus;
  currentImageStatus: ImageStatus;
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
  const [selectedRevisionIndex, setSelectedRevisionIndex] = useState(0);

  async function openDetails(profile: Profile) {
    setDetailsTarget(profile);
    setDetailsRevisions(null);
    try {
      const response = await client.listProfileRevisions(
        { profileId: profile.profileId },
        { headers }
      );
      setDetailsRevisions(response.revisions);
      // Land on the latest revision — that's what "the current state of this profile" means.
      setSelectedRevisionIndex(Math.max(0, response.revisions.length - 1));
    } catch (err: unknown) {
      handleError(err);
    }
  }

  /** Seed the Edit form from a specific revision as a template (saving still appends N+1). */
  function editFromRevision(profileId: string, revision: ProfileRevision) {
    setDetailsTarget(null);
    setEditState({
      profileId,
      currentServiceAccount: revision.targetServiceAccount,
      currentEnvVars: revision.envVars,
      currentSecretEnvVars: revision.secretEnvVars,
      currentDockerImage: revision.dockerImage,
      currentVerificationStatus: revision.verificationStatus,
      currentImageStatus: revision.imageStatus,
    });
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
        <Table.ScrollContainer minWidth={720}>
          <Table striped highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Profile ID</Table.Th>
                <Table.Th>Target service account</Table.Th>
                <Table.Th>Rev</Table.Th>
                <Table.Th>Verification</Table.Th>
                <Table.Th>Status</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(profiles ?? []).map((profile) => {
                const revision = latestRevisions.get(profile.profileId);
                const open = () => void openDetails(profile);
                return (
                  <Table.Tr
                    key={profile.profileId}
                    style={{ cursor: 'pointer' }}
                    // A whole-row click target can't be a <button> (it's a <tr>); role+tabindex+
                    // keydown is the standard accessible pattern for it.
                    // eslint-disable-next-line jsx-a11y/prefer-tag-over-role
                    role="button"
                    tabIndex={0}
                    aria-label={`Open ${profile.profileId}`}
                    onClick={open}
                    onKeyDown={(e) => {
                      if (e.key === 'Enter' || e.key === ' ') {
                        e.preventDefault();
                        open();
                      }
                    }}
                  >
                    <Table.Td>
                      <Text fw={500}>{profile.profileId}</Text>
                    </Table.Td>
                    <Table.Td>
                      <Text size="sm" c="dimmed" style={{ wordBreak: 'break-all' }}>
                        {revision?.targetServiceAccount ?? '—'}
                      </Text>
                    </Table.Td>
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
                    <Table.Td style={{ textAlign: 'right', color: 'var(--mantine-color-dimmed)' }}>
                      ›
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
        title={
          detailsTarget && (
            <Group gap="sm">
              <Title order={4}>{detailsTarget.profileId}</Title>
              {detailsTarget.archived ? (
                <Badge color="gray" variant="outline">
                  Archived
                </Badge>
              ) : (
                <Badge color="blue" variant="outline">
                  Active
                </Badge>
              )}
            </Group>
          )
        }
        size="lg"
      >
        {detailsTarget &&
          (detailsRevisions === null ? (
            <Group justify="center" p="xl">
              <Loader size="sm" />
            </Group>
          ) : (
            (() => {
              const index = Math.min(selectedRevisionIndex, detailsRevisions.length - 1);
              const revision = detailsRevisions[index];
              const grantedNames = grantedWorkerNames(detailsTarget.profileId);
              return (
                <Stack gap="lg">
                  {/*
                   * Profile-scoped actions live in a strip directly under the title — they act on
                   * the whole profile, NOT the revision paged to below. Re-verify in particular
                   * re-checks the profile's *latest* revision, so keeping it out of the revision
                   * body (which can be showing an older revision) avoids implying it targets that.
                   */}
                  <Group gap="xs" align="center">
                    <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
                      Profile
                    </Text>
                    <Button
                      size="xs"
                      variant="default"
                      onClick={() => {
                        void verifyProfile(detailsTarget.profileId);
                        setDetailsTarget(null);
                      }}
                    >
                      Re-verify
                    </Button>
                    <Button
                      size="xs"
                      variant="default"
                      color="red"
                      disabled={detailsTarget.archived}
                      onClick={() => {
                        setArchiveTarget(detailsTarget);
                        setDetailsTarget(null);
                      }}
                    >
                      Archive
                    </Button>
                  </Group>

                  <Divider />

                  <Group justify="space-between">
                    <RevisionPager
                      revisions={detailsRevisions}
                      index={index}
                      onIndex={setSelectedRevisionIndex}
                    />
                    <Text size="xs" c="dimmed">
                      revisions are immutable
                    </Text>
                  </Group>

                  <RevisionView
                    revision={revision}
                    previous={index > 0 ? detailsRevisions[index - 1] : null}
                  />

                  <DetailField label="Granted workers">
                    {grantedNames.length === 0 ? (
                      <Text size="sm" c="dimmed">
                        None
                      </Text>
                    ) : (
                      <Group gap={4}>
                        {grantedNames.map((name) => (
                          <Badge key={name} variant="light">
                            {name}
                          </Badge>
                        ))}
                      </Group>
                    )}
                  </DetailField>

                  {/*
                   * Revision-scoped action — templates a new revision from the one being viewed
                   * (which may not be the latest). Named by revision number so the scope is
                   * unambiguous.
                   */}
                  <Group justify="flex-end" mt="sm">
                    <Button
                      disabled={detailsTarget.archived}
                      onClick={() => editFromRevision(detailsTarget.profileId, revision)}
                    >
                      New revision from Revision {revision.revision}
                    </Button>
                  </Group>
                </Stack>
              );
            })()
          ))}
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
  const [dockerImage, setDockerImage] = useState('');
  const [profileIdError, setProfileIdError] = useState<string | null>(null);
  const [serviceAccountError, setServiceAccountError] = useState<string | null>(null);
  const [imageError, setImageError] = useState<string | null>(null);
  const [envVars, setEnvVars] = useState<EnvRow[]>([]);
  const [secretEnvVars, setSecretEnvVars] = useState<EnvRow[]>([]);
  const [previewNonce, setPreviewNonce] = useState(0);
  const imagePreview = useImagePreview(dockerImage, targetServiceAccount, headers, previewNonce);

  function reset() {
    setProfileId('');
    setDisplayName('');
    setTargetServiceAccount('');
    setNote('');
    setDockerImage('');
    setProfileIdError(null);
    setServiceAccountError(null);
    setImageError(null);
    setEnvVars([]);
    setSecretEnvVars([]);
  }

  async function submit() {
    const idError = validateProfileId(profileId);
    const saError = validateServiceAccount(targetServiceAccount);
    const imgError = validateImageRef(dockerImage);
    setProfileIdError(idError);
    setServiceAccountError(saError);
    setImageError(imgError);
    if (idError || saError || imgError || !envVarRowsAreValid(envVars, secretEnvVars)) {
      return;
    }
    try {
      await client.createProfile(
        {
          profileId,
          displayName,
          targetServiceAccount,
          note,
          dockerImage: dockerImage.trim(),
          // Pin exactly the digest the preview showed; the backend rejects if the tag moved since.
          expectedDockerImageDigest: previewDigest(imagePreview),
          envVars: rowsToRecord(envVars),
          secretEnvVars: rowsToRecord(secretEnvVars),
        },
        { headers }
      );
      toast.success(`Created ${profileId}`);
      reset();
      await onCreated();
    } catch (err: unknown) {
      if (isTagMovedError(err)) {
        // The tag moved between preview and submit — re-resolve so the admin sees the new digest,
        // then re-confirm. They can only ever pin something they've looked at.
        setPreviewNonce((n) => n + 1);
        toast.error(
          'The image tag moved since you previewed it — the digest has been refreshed. Review and create again.'
        );
        return;
      }
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
        <Stack gap={4}>
          <TextInput
            label="Container image"
            description="Optional. Fully-qualified Artifact Registry ref; leave blank for a pure exec/env profile."
            placeholder="us-docker.pkg.dev/project/repo/image:tag"
            value={dockerImage}
            onChange={(e) => setDockerImage(e.currentTarget.value)}
            error={imageError}
          />
          <ImagePreviewLine preview={imagePreview} />
        </Stack>
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
  const [dockerImage, setDockerImage] = useState('');
  const [serviceAccountError, setServiceAccountError] = useState<string | null>(null);
  const [imageError, setImageError] = useState<string | null>(null);
  const [envVars, setEnvVars] = useState<EnvRow[]>([]);
  const [secretEnvVars, setSecretEnvVars] = useState<EnvRow[]>([]);
  const [previewNonce, setPreviewNonce] = useState(0);
  const imagePreview = useImagePreview(dockerImage, targetServiceAccount, headers, previewNonce);

  useEffect(() => {
    if (state) {
      setTargetServiceAccount(state.currentServiceAccount);
      setNote('');
      setDockerImage(state.currentDockerImage);
      setServiceAccountError(null);
      setImageError(null);
      setEnvVars(recordToRows(state.currentEnvVars));
      setSecretEnvVars(recordToRows(state.currentSecretEnvVars));
    }
  }, [state]);

  async function submit() {
    if (!state) {
      return;
    }
    const saError = validateServiceAccount(targetServiceAccount);
    const imgError = validateImageRef(dockerImage);
    setServiceAccountError(saError);
    setImageError(imgError);
    if (saError || imgError || !envVarRowsAreValid(envVars, secretEnvVars)) {
      return;
    }
    try {
      await client.updateProfile(
        {
          profileId: state.profileId,
          targetServiceAccount,
          note,
          dockerImage: dockerImage.trim(),
          expectedDockerImageDigest: previewDigest(imagePreview),
          envVars: rowsToRecord(envVars),
          secretEnvVars: rowsToRecord(secretEnvVars),
        },
        { headers }
      );
      toast.success(`Updated ${state.profileId}`);
      await onUpdated();
    } catch (err: unknown) {
      if (isTagMovedError(err)) {
        setPreviewNonce((n) => n + 1);
        toast.error(
          'The image tag moved since you previewed it — the digest has been refreshed. Review and save again.'
        );
        return;
      }
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
          <Stack gap={4}>
            <TextInput
              label="Container image"
              description="Optional. Fully-qualified Artifact Registry ref; leave blank for a pure exec/env profile."
              placeholder="us-docker.pkg.dev/project/repo/image:tag"
              value={dockerImage}
              onChange={(e) => setDockerImage(e.currentTarget.value)}
              error={imageError}
            />
            <ImagePreviewLine preview={imagePreview} />
          </Stack>
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
