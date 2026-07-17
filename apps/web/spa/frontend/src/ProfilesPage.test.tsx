import { render, screen, waitFor, within } from '@test-utils';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import {
  ImageStatus,
  VerificationStatus,
  type Profile,
  type ProfileRevision,
} from './gen/medusa/workload/v1/fleet_service_pb.ts';

const listProfiles = vi.fn();
const listWorkers = vi.fn();
const listProfileRevisions = vi.fn();
const createProfile = vi.fn();
const updateProfile = vi.fn();
const archiveProfile = vi.fn();
const verifyProfile = vi.fn();

vi.mock('@connectrpc/connect', () => ({
  createClient: () => ({
    listProfiles,
    listWorkers,
    listProfileRevisions,
    createProfile,
    updateProfile,
    archiveProfile,
    verifyProfile,
  }),
}));
vi.mock('@connectrpc/connect-web', () => ({ createGrpcWebTransport: () => ({}) }));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock('./useAuth.tsx', () => ({ useAuth: () => ({ handleUnauthorized: vi.fn() }) }));

const { ProfilesPage } = await import('./ProfilesPage.tsx');

function fakeProfile(overrides: Partial<Profile> = {}): Profile {
  return {
    profileId: 'my-profile-1',
    displayName: '',
    latestRevision: 1,
    archived: false,
    createdAt: '2026-01-01T00:00:00Z',
    ...overrides,
  } as Profile;
}

function fakeRevision(overrides: Partial<ProfileRevision> = {}): ProfileRevision {
  return {
    profileId: 'my-profile-1',
    revision: 1,
    targetServiceAccount: 'sa@project.iam.gserviceaccount.com',
    createdAt: '2026-01-01T00:00:00Z',
    createdBy: 'admin@example.com',
    note: '',
    verificationStatus: VerificationStatus.VERIFIED,
    envVars: {},
    secretEnvVars: {},
    dockerImage: '',
    dockerImageDigest: '',
    imageStatus: ImageStatus.NOT_APPLICABLE,
    ...overrides,
  } as ProfileRevision;
}

beforeEach(() => {
  listProfiles.mockReset();
  listWorkers.mockReset();
  listProfileRevisions.mockReset();
  createProfile.mockReset();
  updateProfile.mockReset();
  archiveProfile.mockReset();
  verifyProfile.mockReset();
  listWorkers.mockResolvedValue({ workers: [] });
  listProfileRevisions.mockResolvedValue({ revisions: [fakeRevision()] });
  createProfile.mockResolvedValue({});
  updateProfile.mockResolvedValue({});
  archiveProfile.mockResolvedValue({});
  verifyProfile.mockResolvedValue({});
});

test('lists a profile with its target service account and verification status', async () => {
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  expect(await screen.findByText('my-profile-1')).toBeInTheDocument();
  expect(screen.getByText('sa@project.iam.gserviceaccount.com')).toBeInTheDocument();
  expect(screen.getByText('Verified')).toBeInTheDocument();
});

test('shows a binding-missing profile with a remediation tooltip', async () => {
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  listProfileRevisions.mockResolvedValue({
    revisions: [fakeRevision({ verificationStatus: VerificationStatus.BINDING_MISSING })],
  });
  render(<ProfilesPage token="tok" />);

  expect(await screen.findByText('Binding missing')).toBeInTheDocument();
});

test('creating a profile validates the ID and service account client-side', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Create profile' }));
  const dialog = await screen.findByRole('dialog');

  await user.type(within(dialog).getByLabelText(/Profile ID/), 'X');
  await user.type(within(dialog).getByLabelText(/Target service account/), 'not-an-email');
  await user.click(within(dialog).getByRole('button', { name: 'Create' }));

  expect(await within(dialog).findByText(/lowercase letters/)).toBeInTheDocument();
  expect(within(dialog).getByText(/GCP service account email/)).toBeInTheDocument();
  expect(createProfile).not.toHaveBeenCalled();
});

test('creating a valid profile calls createProfile and refreshes', async () => {
  const user = userEvent.setup();
  listProfiles
    .mockResolvedValueOnce({ profiles: [] })
    .mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Create profile' }));
  const dialog = await screen.findByRole('dialog');

  await user.type(within(dialog).getByLabelText(/Profile ID/), 'my-profile-1');
  await user.type(
    within(dialog).getByLabelText(/Target service account/),
    'sa@project.iam.gserviceaccount.com'
  );
  await user.click(within(dialog).getByRole('button', { name: 'Create' }));

  await waitFor(() => {
    expect(createProfile).toHaveBeenCalledWith(
      {
        profileId: 'my-profile-1',
        displayName: '',
        targetServiceAccount: 'sa@project.iam.gserviceaccount.com',
        note: '',
        dockerImage: '',
        envVars: {},
        secretEnvVars: {},
      },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('editing a profile appends a revision without asking for a new ID', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText(/creates revision/)).toBeInTheDocument();

  const saInput = within(dialog).getByLabelText(/Target service account/);
  await user.clear(saInput);
  await user.type(saInput, 'sa-v2@project.iam.gserviceaccount.com');
  await user.click(within(dialog).getByRole('button', { name: 'Save' }));

  await waitFor(() => {
    expect(updateProfile).toHaveBeenCalledWith(
      {
        profileId: 'my-profile-1',
        targetServiceAccount: 'sa-v2@project.iam.gserviceaccount.com',
        note: '',
        dockerImage: '',
        envVars: {},
        secretEnvVars: {},
      },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('archiving a profile requires confirmation', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Archive' }));
  const dialog = await screen.findByRole('dialog');
  await user.click(within(dialog).getByRole('button', { name: 'Archive' }));

  await waitFor(() => {
    expect(archiveProfile).toHaveBeenCalledWith(
      { profileId: 'my-profile-1' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('an archived profile cannot be edited or archived again', async () => {
  listProfiles.mockResolvedValue({ profiles: [fakeProfile({ archived: true })] });
  render(<ProfilesPage token="tok" />);

  expect(await screen.findByText('Archived')).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Edit' })).toBeDisabled();
  expect(screen.getByRole('button', { name: 'Archive' })).toBeDisabled();
});

test('re-verifying a profile', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Re-verify' }));

  await waitFor(() => {
    expect(verifyProfile).toHaveBeenCalledWith(
      { profileId: 'my-profile-1' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('profile details show revision history and granted workers', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  listWorkers.mockResolvedValue({
    workers: [
      {
        workerId: 'worker-1',
        name: 'jakub-mbp',
        grantedProfileIds: ['my-profile-1'],
      } as never,
    ],
  });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Details' }));
  const dialog = await screen.findByRole('dialog');

  expect(within(dialog).getByText('Revision history')).toBeInTheDocument();
  expect(within(dialog).getByText('Granted workers')).toBeInTheDocument();
  expect(await within(dialog).findByText('jakub-mbp')).toBeInTheDocument();
});

test('creating a profile with an env var and a secret env var sends both maps', async () => {
  const user = userEvent.setup();
  listProfiles
    .mockResolvedValueOnce({ profiles: [] })
    .mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Create profile' }));
  const dialog = await screen.findByRole('dialog');

  await user.type(within(dialog).getByLabelText(/Profile ID/), 'my-profile-1');
  await user.type(
    within(dialog).getByLabelText(/Target service account/),
    'sa@project.iam.gserviceaccount.com'
  );

  await user.click(within(dialog).getByRole('button', { name: 'Add env vars' }));
  const [nameInput] = within(dialog).getAllByPlaceholderText('NAME');
  await user.type(nameInput, 'MODE');
  const [valueInput] = within(dialog).getAllByLabelText('Value');
  await user.type(valueInput, 'batch');

  await user.click(within(dialog).getByRole('button', { name: 'Add secret env vars' }));
  const nameInputs = within(dialog).getAllByPlaceholderText('NAME');
  await user.type(nameInputs[1], 'API_KEY');
  const secretInput = within(dialog).getByLabelText('Secret Manager resource name');
  await user.type(secretInput, 'projects/p/secrets/api-key/versions/latest');

  await user.click(within(dialog).getByRole('button', { name: 'Create' }));

  await waitFor(() => {
    expect(createProfile).toHaveBeenCalledWith(
      {
        profileId: 'my-profile-1',
        displayName: '',
        targetServiceAccount: 'sa@project.iam.gserviceaccount.com',
        note: '',
        dockerImage: '',
        envVars: { MODE: 'batch' },
        secretEnvVars: { API_KEY: 'projects/p/secrets/api-key/versions/latest' },
      },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('an invalid env var name is rejected client-side without calling createProfile', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Create profile' }));
  const dialog = await screen.findByRole('dialog');

  await user.type(within(dialog).getByLabelText(/Profile ID/), 'my-profile-1');
  await user.type(
    within(dialog).getByLabelText(/Target service account/),
    'sa@project.iam.gserviceaccount.com'
  );
  await user.click(within(dialog).getByRole('button', { name: 'Add env vars' }));
  const [nameInput] = within(dialog).getAllByPlaceholderText('NAME');
  await user.type(nameInput, 'not-valid');

  await user.click(within(dialog).getByRole('button', { name: 'Create' }));

  expect(await within(dialog).findByText(/uppercase letters/)).toBeInTheDocument();
  expect(createProfile).not.toHaveBeenCalled();
});

test('a malformed secret resource name is rejected client-side', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Create profile' }));
  const dialog = await screen.findByRole('dialog');

  await user.type(within(dialog).getByLabelText(/Profile ID/), 'my-profile-1');
  await user.type(
    within(dialog).getByLabelText(/Target service account/),
    'sa@project.iam.gserviceaccount.com'
  );
  await user.click(within(dialog).getByRole('button', { name: 'Add secret env vars' }));
  const [nameInput] = within(dialog).getAllByPlaceholderText('NAME');
  await user.type(nameInput, 'API_KEY');
  const secretInput = within(dialog).getByLabelText('Secret Manager resource name');
  await user.type(secretInput, 'not-a-resource-name');

  await user.click(within(dialog).getByRole('button', { name: 'Create' }));

  expect(await within(dialog).findByText(/projects\/<project>/)).toBeInTheDocument();
  expect(createProfile).not.toHaveBeenCalled();
});

test('editing a profile pre-populates its existing env vars', async () => {
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  listProfileRevisions.mockResolvedValue({
    revisions: [fakeRevision({ envVars: { MODE: 'batch' } })],
  });
  const user = userEvent.setup();
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  const dialog = await screen.findByRole('dialog');

  expect(within(dialog).getByDisplayValue('MODE')).toBeInTheDocument();
  expect(within(dialog).getByDisplayValue('batch')).toBeInTheDocument();
});

test('a secret_inaccessible profile shows the remediation alert when editing', async () => {
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  listProfileRevisions.mockResolvedValue({
    revisions: [
      fakeRevision({
        verificationStatus: VerificationStatus.SECRET_INACCESSIBLE,
        secretEnvVars: { API_KEY: 'projects/p/secrets/api-key/versions/latest' },
      }),
    ],
  });
  const user = userEvent.setup();
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Edit' }));
  const dialog = await screen.findByRole('dialog');

  expect(await within(dialog).findByText(/secret_ids input/)).toBeInTheDocument();
});

test('revision history shows an env diff between adjacent revisions', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  listProfileRevisions.mockResolvedValue({
    revisions: [
      fakeRevision({ revision: 1, envVars: { MODE: 'batch', OLD_VAR: 'x' } }),
      fakeRevision({ revision: 2, envVars: { MODE: 'streaming', NEW_VAR: 'y' } }),
    ],
  });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Details' }));
  const dialog = await screen.findByRole('dialog');

  expect(within(dialog).getByText('Initial revision')).toBeInTheDocument();
  expect(within(dialog).getByText('+NEW_VAR')).toBeInTheDocument();
  expect(within(dialog).getByText('~MODE')).toBeInTheDocument();
  expect(within(dialog).getByText('-OLD_VAR')).toBeInTheDocument();
});

test('creating a profile with an image passes docker_image to createProfile', async () => {
  const user = userEvent.setup();
  listProfiles
    .mockResolvedValueOnce({ profiles: [] })
    .mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Create profile' }));
  const dialog = await screen.findByRole('dialog');

  await user.type(within(dialog).getByLabelText(/Profile ID/), 'my-profile-1');
  await user.type(
    within(dialog).getByLabelText(/Target service account/),
    'sa@project.iam.gserviceaccount.com'
  );
  await user.type(
    within(dialog).getByLabelText(/Container image/),
    'us-docker.pkg.dev/p/repo/app:v1'
  );
  await user.click(within(dialog).getByRole('button', { name: 'Create' }));

  await waitFor(() => {
    expect(createProfile).toHaveBeenCalledWith(
      expect.objectContaining({ dockerImage: 'us-docker.pkg.dev/p/repo/app:v1' }),
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('a non-Google registry image ref is rejected client-side without calling createProfile', async () => {
  // Security rule, not a preference: the backend and the worker both authenticate to the image's
  // registry with a token impersonating the profile's target SA, so a third-party host would be
  // handed a live credential for that account.
  const user = userEvent.setup();
  listProfiles
    .mockResolvedValueOnce({ profiles: [] })
    .mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Create profile' }));
  const dialog = await screen.findByRole('dialog');

  await user.type(within(dialog).getByLabelText(/Profile ID/), 'my-profile-1');
  await user.type(
    within(dialog).getByLabelText(/Target service account/),
    'sa@project.iam.gserviceaccount.com'
  );
  await user.type(within(dialog).getByLabelText(/Container image/), 'ghcr.io/someone/app:v1');
  await user.click(within(dialog).getByRole('button', { name: 'Create' }));

  expect(await within(dialog).findByText(/Google container registry/)).toBeInTheDocument();
  expect(createProfile).not.toHaveBeenCalled();
});

test('a bare Docker Hub image ref is rejected client-side without calling createProfile', async () => {
  const user = userEvent.setup();
  listProfiles
    .mockResolvedValueOnce({ profiles: [] })
    .mockResolvedValue({ profiles: [fakeProfile()] });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Create profile' }));
  const dialog = await screen.findByRole('dialog');

  await user.type(within(dialog).getByLabelText(/Profile ID/), 'my-profile-1');
  await user.type(
    within(dialog).getByLabelText(/Target service account/),
    'sa@project.iam.gserviceaccount.com'
  );
  await user.type(within(dialog).getByLabelText(/Container image/), 'busybox:latest');
  await user.click(within(dialog).getByRole('button', { name: 'Create' }));

  expect(await within(dialog).findByText(/fully-qualified registry ref/)).toBeInTheDocument();
  expect(createProfile).not.toHaveBeenCalled();
});

test('revision history surfaces a digest change under an identical tag', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  listProfileRevisions.mockResolvedValue({
    revisions: [
      fakeRevision({
        revision: 1,
        dockerImage: 'us-docker.pkg.dev/p/repo/app:v1',
        dockerImageDigest: 'sha256:aaaaaaaa11111111',
        imageStatus: ImageStatus.RESOLVED,
      }),
      // Same tag, different digest — a re-push. The diff must call this out.
      fakeRevision({
        revision: 2,
        dockerImage: 'us-docker.pkg.dev/p/repo/app:v1',
        dockerImageDigest: 'sha256:bbbbbbbb22222222',
        imageStatus: ImageStatus.RESOLVED,
      }),
    ],
  });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Details' }));
  const dialog = await screen.findByRole('dialog');

  expect(within(dialog).getByText('image digest')).toBeInTheDocument();
  // Both revisions show the tag; the short digest is rendered too.
  expect(within(dialog).getByText('sha256:bbbbbbbb')).toBeInTheDocument();
});

test('an unresolvable image revision shows the flagged badge in history', async () => {
  const user = userEvent.setup();
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
  listProfileRevisions.mockResolvedValue({
    revisions: [
      fakeRevision({
        revision: 1,
        dockerImage: 'us-docker.pkg.dev/p/repo/missing:v9',
        dockerImageDigest: '',
        imageStatus: ImageStatus.UNRESOLVABLE,
      }),
    ],
  });
  render(<ProfilesPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Details' }));
  const dialog = await screen.findByRole('dialog');

  expect(within(dialog).getByText('Unresolvable')).toBeInTheDocument();
});
