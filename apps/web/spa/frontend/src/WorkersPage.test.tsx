import { render, screen, waitFor, within } from '@test-utils';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import {
  type EnrollmentToken,
  WorkerStatus,
  type Profile,
  type Worker,
} from './gen/medusa/workload/v1/fleet_service_pb.ts';

const listWorkers = vi.fn();
const listProfiles = vi.fn();
const listEnrollmentTokens = vi.fn();
const approveWorker = vi.fn();
const rejectWorker = vi.fn();
const revokeWorker = vi.fn();
const grantProfile = vi.fn();
const revokeProfileGrant = vi.fn();
const createEnrollmentToken = vi.fn();
const revokeEnrollmentToken = vi.fn();

vi.mock('@connectrpc/connect', () => ({
  createClient: () => ({
    listWorkers,
    listProfiles,
    listEnrollmentTokens,
    approveWorker,
    rejectWorker,
    revokeWorker,
    grantProfile,
    revokeProfileGrant,
    createEnrollmentToken,
    revokeEnrollmentToken,
  }),
}));
vi.mock('@connectrpc/connect-web', () => ({ createGrpcWebTransport: () => ({}) }));
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock('./useAuth.tsx', () => ({ useAuth: () => ({ handleUnauthorized: vi.fn() }) }));

const { WorkersPage } = await import('./WorkersPage.tsx');

function fakeWorker(overrides: Partial<Worker> = {}): Worker {
  return {
    workerId: 'worker-1',
    name: 'jakub-mbp',
    hostname: 'jakub.local',
    os: 'macos',
    cliVersion: '1.0.0',
    status: WorkerStatus.PENDING,
    confirmationCode: '4913',
    createdAt: '2026-01-01T00:00:00Z',
    approvedAt: '',
    approvedBy: '',
    lastSeenAt: '',
    grantedProfileIds: [],
    registeredVia: 'v1',
    sourceIp: '',
    ...overrides,
  } as Worker;
}

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

function fakeEnrollmentToken(overrides: Partial<EnrollmentToken> = {}): EnrollmentToken {
  return {
    enrollmentTokenId: 'token-1',
    note: "kuba's mbp",
    createdBy: 'admin@medusa.software',
    createdAt: '2026-01-01T00:00:00Z',
    expiresAt: '2026-01-08T00:00:00Z',
    requireApproval: false,
    ...overrides,
  } as EnrollmentToken;
}

beforeEach(() => {
  listWorkers.mockReset();
  listProfiles.mockReset();
  listEnrollmentTokens.mockReset();
  approveWorker.mockReset();
  rejectWorker.mockReset();
  revokeWorker.mockReset();
  grantProfile.mockReset();
  revokeProfileGrant.mockReset();
  createEnrollmentToken.mockReset();
  revokeEnrollmentToken.mockReset();
  listProfiles.mockResolvedValue({ profiles: [] });
  listEnrollmentTokens.mockResolvedValue({ enrollmentTokens: [] });
  approveWorker.mockResolvedValue({});
  rejectWorker.mockResolvedValue({});
  revokeWorker.mockResolvedValue({});
  grantProfile.mockResolvedValue({});
  revokeProfileGrant.mockResolvedValue({});
  createEnrollmentToken.mockResolvedValue({});
  revokeEnrollmentToken.mockResolvedValue({});
});

test('shows a pending worker with its confirmation code', async () => {
  listWorkers.mockResolvedValue({ workers: [fakeWorker()] });
  render(<WorkersPage token="tok" />);

  expect(await screen.findByText('jakub-mbp')).toBeInTheDocument();
  expect(screen.getByText('4913')).toBeInTheDocument();
  expect(screen.getByText('No active workers.')).toBeInTheDocument();
});

test('a v2 pending worker shows its source IP instead of a confirmation code', async () => {
  listWorkers.mockResolvedValue({
    workers: [
      fakeWorker({
        status: WorkerStatus.PENDING,
        confirmationCode: '',
        registeredVia: 'v2',
        sourceIp: '203.0.113.7',
      }),
    ],
  });
  render(<WorkersPage token="tok" />);

  expect(await screen.findByText('203.0.113.7')).toBeInTheDocument();
  expect(screen.queryByText('4913')).not.toBeInTheDocument();
});

test('an active v2 worker is badged v2', async () => {
  listWorkers.mockResolvedValue({
    workers: [
      fakeWorker({
        status: WorkerStatus.ACTIVE,
        confirmationCode: '',
        registeredVia: 'v2',
      }),
    ],
  });
  render(<WorkersPage token="tok" />);

  expect(await screen.findByText('jakub-mbp')).toBeInTheDocument();
  expect(screen.getByText('v2')).toBeInTheDocument();
});

test('does not show a confirmation code for an active worker', async () => {
  listWorkers.mockResolvedValue({
    workers: [fakeWorker({ status: WorkerStatus.ACTIVE, confirmationCode: '' })],
  });
  render(<WorkersPage token="tok" />);

  expect(await screen.findByText('jakub-mbp')).toBeInTheDocument();
  expect(screen.queryByText('4913')).not.toBeInTheDocument();
  expect(screen.getByText('No workers are pending approval.')).toBeInTheDocument();
});

test('approving a lone pending worker requires only a plain confirmation', async () => {
  const user = userEvent.setup();
  listWorkers.mockResolvedValue({ workers: [fakeWorker()] });
  render(<WorkersPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Approve' }));

  const dialog = await screen.findByRole('dialog');
  expect(within(dialog).getByText(/Does the requester/)).toBeInTheDocument();
  expect(within(dialog).queryByRole('checkbox')).not.toBeInTheDocument();

  await user.click(within(dialog).getByRole('button', { name: 'Approve' }));

  await waitFor(() => {
    expect(approveWorker).toHaveBeenCalledWith(
      { workerId: 'worker-1' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('with two pending workers, shows the multi-pending warning and requires the checkbox', async () => {
  const user = userEvent.setup();
  listWorkers.mockResolvedValue({
    workers: [
      fakeWorker({ workerId: 'worker-1', name: 'worker-a', confirmationCode: '1111' }),
      fakeWorker({ workerId: 'worker-2', name: 'worker-b', confirmationCode: '2222' }),
    ],
  });
  render(<WorkersPage token="tok" />);

  expect(await screen.findByText('Multiple pending registrations')).toBeInTheDocument();

  const approveButtons = await screen.findAllByRole('button', { name: 'Approve' });
  await user.click(approveButtons[0]);

  const dialog = await screen.findByRole('dialog');
  const confirmButton = within(dialog).getByRole('button', { name: 'Approve' });
  expect(confirmButton).toBeDisabled();

  await user.click(within(dialog).getByRole('checkbox'));
  expect(confirmButton).toBeEnabled();

  await user.click(confirmButton);
  await waitFor(() => {
    expect(approveWorker).toHaveBeenCalledWith(
      { workerId: 'worker-1' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('revoking an active worker', async () => {
  const user = userEvent.setup();
  listWorkers.mockResolvedValue({
    workers: [
      fakeWorker({
        status: WorkerStatus.ACTIVE,
        confirmationCode: '',
        grantedProfileIds: ['my-profile-1'],
      }),
    ],
  });
  render(<WorkersPage token="tok" />);

  expect(await screen.findByText('my-profile-1')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Revoke' }));
  const dialog = await screen.findByRole('dialog');
  await user.click(within(dialog).getByRole('button', { name: 'Revoke' }));

  await waitFor(() => {
    expect(revokeWorker).toHaveBeenCalledWith(
      { workerId: 'worker-1' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('granting a profile to an active worker', async () => {
  const user = userEvent.setup();
  listWorkers.mockResolvedValue({
    workers: [
      fakeWorker({ status: WorkerStatus.ACTIVE, confirmationCode: '', grantedProfileIds: [] }),
    ],
  });
  listProfiles.mockResolvedValue({
    profiles: [
      fakeProfile({ profileId: 'my-profile-1' }),
      fakeProfile({ profileId: 'archived-profile', archived: true }),
    ],
  });
  render(<WorkersPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Manage grants' }));
  const dialog = await screen.findByRole('dialog');

  const select = within(dialog).getByPlaceholderText('Pick a profile');
  await user.click(select);
  expect(screen.queryByText('archived-profile')).not.toBeInTheDocument();
  await user.click(await screen.findByText('my-profile-1'));

  await user.click(within(dialog).getByRole('button', { name: 'Grant' }));

  await waitFor(() => {
    expect(grantProfile).toHaveBeenCalledWith(
      { workerId: 'worker-1', profileId: 'my-profile-1' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('revoking a grant from the manage-grants dialog', async () => {
  const user = userEvent.setup();
  listWorkers.mockResolvedValue({
    workers: [
      fakeWorker({
        status: WorkerStatus.ACTIVE,
        confirmationCode: '',
        grantedProfileIds: ['my-profile-1'],
      }),
    ],
  });
  render(<WorkersPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'Manage grants' }));
  const dialog = await screen.findByRole('dialog');

  await user.click(within(dialog).getByRole('button', { name: 'Revoke my-profile-1' }));

  await waitFor(() => {
    expect(revokeProfileGrant).toHaveBeenCalledWith(
      { workerId: 'worker-1', profileId: 'my-profile-1' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('creating an enrollment token shows it once, then never again after the dialog closes', async () => {
  const user = userEvent.setup();
  listWorkers.mockResolvedValue({ workers: [] });
  createEnrollmentToken.mockResolvedValue({
    token: 'wle_the-secret-token-value',
    enrollmentToken: fakeEnrollmentToken(),
  });
  render(<WorkersPage token="tok" />);

  await user.click(await screen.findByRole('button', { name: 'New worker' }));
  const dialog = await screen.findByRole('dialog');
  await user.type(within(dialog).getByLabelText('Note'), "kuba's mbp");
  await user.click(within(dialog).getByRole('button', { name: 'Create token' }));

  // The plaintext is revealed exactly once, with the copy affordance.
  expect(await screen.findByText('wle_the-secret-token-value')).toBeInTheDocument();
  expect(screen.getByText(/only time the token is shown/i)).toBeInTheDocument();

  await waitFor(() => {
    expect(createEnrollmentToken).toHaveBeenCalledWith(
      { note: "kuba's mbp", expiresInDays: 7, requireApproval: false },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });

  // Closing the dialog wipes the token — it must never be displayed again.
  await user.click(screen.getByRole('button', { name: 'Done' }));
  await waitFor(() => {
    expect(screen.queryByText('wle_the-secret-token-value')).not.toBeInTheDocument();
  });

  // Reopening "New worker" shows the form afresh, not the old token.
  await user.click(screen.getByRole('button', { name: 'New worker' }));
  expect(await screen.findByRole('button', { name: 'Create token' })).toBeInTheDocument();
  expect(screen.queryByText('wle_the-secret-token-value')).not.toBeInTheDocument();
});

test('lists an outstanding enrollment token and revokes it', async () => {
  const user = userEvent.setup();
  listWorkers.mockResolvedValue({ workers: [] });
  listEnrollmentTokens.mockResolvedValue({
    enrollmentTokens: [fakeEnrollmentToken({ enrollmentTokenId: 'token-42', note: 'laptop' })],
  });
  render(<WorkersPage token="tok" />);

  expect(await screen.findByText('laptop')).toBeInTheDocument();
  expect(screen.getByText('admin@medusa.software')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Revoke' }));
  const dialog = await screen.findByRole('dialog');
  await user.click(within(dialog).getByRole('button', { name: 'Revoke' }));

  await waitFor(() => {
    expect(revokeEnrollmentToken).toHaveBeenCalledWith(
      { enrollmentTokenId: 'token-42' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});
