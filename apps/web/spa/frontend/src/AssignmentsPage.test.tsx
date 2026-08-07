import { render, screen, waitFor, within } from '@test-utils';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import type { Assignment, Profile, Worker } from './gen/medusa/workload/v1/fleet_service_pb.ts';

const listAssignments = vi.fn();
const listWorkers = vi.fn();
const listProfiles = vi.fn();
const createAssignment = vi.fn();
const deleteAssignment = vi.fn();

vi.mock('@connectrpc/connect', () => ({
  createClient: () => ({
    listAssignments,
    listWorkers,
    listProfiles,
    createAssignment,
    deleteAssignment,
  }),
}));
vi.mock('@connectrpc/connect-web', () => ({ createGrpcWebTransport: () => ({}) }));
vi.mock('./useAuth.tsx', () => ({ useAuth: () => ({ handleUnauthorized: vi.fn() }) }));

const { AssignmentsPage } = await import('./AssignmentsPage.tsx');

function fakeAssignment(overrides: Partial<Assignment> = {}): Assignment {
  return {
    assignmentId: 'a-1',
    workerId: 'w-1',
    profileId: 'flow-worker',
    createdAt: '2026-07-22T10:00:00Z',
    createdBy: 'admin@example.com',
    ...overrides,
  } as Assignment;
}

function fakeWorker(overrides: Partial<Worker> = {}): Worker {
  return { workerId: 'w-1', name: 'tux-worker', ...overrides } as Worker;
}

function fakeProfile(overrides: Partial<Profile> = {}): Profile {
  return { profileId: 'flow-worker', archived: false, ...overrides } as Profile;
}

beforeEach(() => {
  listAssignments.mockReset();
  listWorkers.mockReset();
  listProfiles.mockReset();
  createAssignment.mockReset();
  deleteAssignment.mockReset();
  listAssignments.mockResolvedValue({ assignments: [] });
  listWorkers.mockResolvedValue({ workers: [fakeWorker()] });
  listProfiles.mockResolvedValue({ profiles: [fakeProfile()] });
});

test('renders an assignment with worker name, profile, and who created it', async () => {
  listAssignments.mockResolvedValue({ assignments: [fakeAssignment()] });
  render(<AssignmentsPage token="tok" />);

  // The worker name and profile id also render as Select option labels, so scope the
  // assertions to the assignment's table row (anchored on the unique "created by" cell).
  const row = (await screen.findByText('admin@example.com')).closest('tr') as HTMLElement;
  expect(within(row).getByText('tux-worker')).toBeInTheDocument();
  expect(within(row).getByText('flow-worker')).toBeInTheDocument();
  expect(within(row).getByText('admin@example.com')).toBeInTheDocument();

  await waitFor(() => {
    expect(listAssignments).toHaveBeenCalledWith(
      { workerId: '', profileId: '' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('shows an empty note when there are no assignments', async () => {
  render(<AssignmentsPage token="tok" />);
  expect(await screen.findByText('No assignments.')).toBeInTheDocument();
});

test('creating an assignment posts both ids and refreshes', async () => {
  const user = userEvent.setup();
  createAssignment.mockResolvedValue({ assignment: fakeAssignment() });
  render(<AssignmentsPage token="tok" />);
  await waitFor(() => expect(listWorkers).toHaveBeenCalled());

  await user.click(screen.getByPlaceholderText('Pick a worker'));
  await user.click(await screen.findByText('tux-worker'));
  await user.click(screen.getByPlaceholderText('Pick a profile'));
  await user.click(await screen.findByText('flow-worker'));
  await user.click(screen.getByRole('button', { name: 'Create assignment' }));

  await waitFor(() => {
    expect(createAssignment).toHaveBeenCalledWith(
      { workerId: 'w-1', profileId: 'flow-worker' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('deleting an assignment posts its id and refreshes', async () => {
  const user = userEvent.setup();
  listAssignments.mockResolvedValue({ assignments: [fakeAssignment()] });
  deleteAssignment.mockResolvedValue({});
  render(<AssignmentsPage token="tok" />);

  // "flow-worker" also appears as a Select option label; wait on the unique row cell instead.
  await screen.findByText('admin@example.com');
  await user.click(screen.getByRole('button', { name: 'Delete' }));

  await waitFor(() => {
    expect(deleteAssignment).toHaveBeenCalledWith(
      { assignmentId: 'a-1' },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});
