import { render, screen, waitFor, within } from '@test-utils';
import userEvent from '@testing-library/user-event';
import { beforeEach, expect, test, vi } from 'vitest';
import { RunKind, RunState, type Run } from './gen/medusa/workload/v1/fleet_service_pb.ts';

const listRuns = vi.fn();

vi.mock('@connectrpc/connect', () => ({ createClient: () => ({ listRuns }) }));
vi.mock('@connectrpc/connect-web', () => ({ createGrpcWebTransport: () => ({}) }));
vi.mock('./useAuth.tsx', () => ({ useAuth: () => ({ handleUnauthorized: vi.fn() }) }));

const { RunsPage } = await import('./RunsPage.tsx');

function fakeRun(overrides: Partial<Run> = {}): Run {
  return {
    runId: 'abcdef12-0000-0000-0000-000000000000',
    workerId: 'worker-1',
    workerName: 'tux-worker',
    profileId: 'flow-worker',
    revision: 4,
    kind: RunKind.RUN,
    state: RunState.RUNNING,
    exitCode: 0,
    hasExitCode: false,
    startedAt: '2026-07-22T10:00:00Z',
    lastHeartbeatAt: '2026-07-22T10:00:00Z',
    endedAt: '',
    imageDigest: 'sha256:abc',
    ...overrides,
  } as Run;
}

beforeEach(() => {
  listRuns.mockReset();
  listRuns.mockResolvedValue({ runs: [] });
});

test('renders a running run with its state, profile@rev, and worker name', async () => {
  listRuns.mockResolvedValue({ runs: [fakeRun()] });
  render(<RunsPage token="tok" />);

  expect(await screen.findByText('flow-worker@4')).toBeInTheDocument();
  expect(screen.getByText('tux-worker')).toBeInTheDocument();
  expect(screen.getByText('running')).toBeInTheDocument();

  // Live-only by default, with the bearer header.
  await waitFor(() => {
    expect(listRuns).toHaveBeenCalledWith(
      { profileId: '', workerId: '', liveOnly: true },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('a succeeded run shows its exit code and duration; a lost run shows neither', async () => {
  listRuns.mockResolvedValue({
    runs: [
      fakeRun({
        runId: 'ok',
        state: RunState.SUCCEEDED,
        kind: RunKind.EXEC,
        hasExitCode: true,
        exitCode: 0,
        endedAt: '2026-07-22T10:04:01Z',
      }),
      fakeRun({ runId: 'gone', state: RunState.LOST }),
    ],
  });
  render(<RunsPage token="tok" />);

  const okRow = (await screen.findByText('succeeded')).closest('tr') as HTMLElement;
  expect(within(okRow).getByText('4m01s')).toBeInTheDocument();
  expect(within(okRow).getByText('exec')).toBeInTheDocument();

  const lostRow = screen.getByText('lost').closest('tr') as HTMLElement;
  // Lost: duration and exit both render as em dashes.
  expect(within(lostRow).getAllByText('—').length).toBeGreaterThanOrEqual(2);
});

test('toggling "Include finished" flips liveOnly', async () => {
  const user = userEvent.setup();
  render(<RunsPage token="tok" />);
  await waitFor(() => expect(listRuns).toHaveBeenCalled());

  await user.click(screen.getByLabelText('Include finished'));

  await waitFor(() => {
    expect(listRuns).toHaveBeenCalledWith(
      { profileId: '', workerId: '', liveOnly: false },
      { headers: { Authorization: 'Bearer tok' } }
    );
  });
});

test('shows an empty note when there are no in-flight runs', async () => {
  listRuns.mockResolvedValue({ runs: [] });
  render(<RunsPage token="tok" />);
  expect(await screen.findByText(/No in-flight runs/)).toBeInTheDocument();
});
