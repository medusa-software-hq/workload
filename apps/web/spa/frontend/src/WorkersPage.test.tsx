import { render, screen, waitFor, within } from '@test-utils';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { WorkerStatus, type Worker } from './gen/medusa/workload/v1/fleet_service_pb.ts';

const listWorkers = vi.fn();
const approveWorker = vi.fn();
const rejectWorker = vi.fn();
const revokeWorker = vi.fn();

vi.mock('@connectrpc/connect', () => ({
  createClient: () => ({ listWorkers, approveWorker, rejectWorker, revokeWorker }),
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
    ...overrides,
  } as Worker;
}

beforeEach(() => {
  listWorkers.mockReset();
  approveWorker.mockReset();
  rejectWorker.mockReset();
  revokeWorker.mockReset();
  approveWorker.mockResolvedValue({});
  rejectWorker.mockResolvedValue({});
  revokeWorker.mockResolvedValue({});
});

test('shows a pending worker with its confirmation code', async () => {
  listWorkers.mockResolvedValue({ workers: [fakeWorker()] });
  render(<WorkersPage token="tok" />);

  expect(await screen.findByText('jakub-mbp')).toBeInTheDocument();
  expect(screen.getByText('4913')).toBeInTheDocument();
  expect(screen.getByText('No active workers.')).toBeInTheDocument();
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
