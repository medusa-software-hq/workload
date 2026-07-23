import { createClient } from '@connectrpc/connect';
import { createGrpcWebTransport } from '@connectrpc/connect-web';
import {
  Alert,
  Badge,
  Checkbox,
  Group,
  Loader,
  Stack,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  FleetService,
  RunKind,
  RunState,
  type Run,
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
 * Elapsed time of a run: ended − started, or now − started while still going. `—` for a lost run
 * (its true end is unknown). Rendered like the CLI: `3h12m` / `4m01s` / `45s`.
 */
function formatDuration(run: Run, now: number): string {
  if (run.state === RunState.LOST || !run.startedAt) {
    return '—';
  }
  const from = new Date(run.startedAt).getTime();
  const to = run.endedAt ? new Date(run.endedAt).getTime() : now;
  const total = Math.max(0, Math.floor((to - from) / 1000));
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) {
    return `${h}h${m}m`;
  }
  if (m > 0) {
    return `${m}m${String(s).padStart(2, '0')}s`;
  }
  return `${s}s`;
}

function runKindLabel(kind: RunKind): string {
  switch (kind) {
    case RunKind.RUN:
      return 'run';
    case RunKind.EXEC:
      return 'exec';
    default:
      return '—';
  }
}

/** A colored pill per run state, modeled on ProfilesPage's VerificationBadge. */
export function RunStateBadge({ state }: { state: RunState }) {
  const { label, color } = runStateDisplay(state);
  return (
    <Badge variant="light" color={color}>
      {label}
    </Badge>
  );
}

function runStateDisplay(state: RunState): { label: string; color: string } {
  switch (state) {
    case RunState.RUNNING:
      return { label: 'running', color: 'blue' };
    case RunState.SUCCEEDED:
      return { label: 'succeeded', color: 'green' };
    case RunState.FAILED:
      return { label: 'failed', color: 'red' };
    case RunState.LOST:
      return { label: 'lost', color: 'orange' };
    default:
      return { label: 'unknown', color: 'gray' };
  }
}

/**
 * The runs page: who is running what, right now and recently. Polls `ListRuns` on the same 5s
 * cadence as the other pages, so a run's `running → lost` transition (a read-time derivation from a
 * stale heartbeat) appears on its own. Live-only by default; a toggle includes finished runs. Free
 * text filters by profile or worker id.
 */
export function RunsPage({ token }: { token: string }) {
  const { handleUnauthorized } = useAuth();
  const [runs, setRuns] = useState<Run[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [profileFilter, setProfileFilter] = useState('');
  const [workerFilter, setWorkerFilter] = useState('');
  const [includeFinished, setIncludeFinished] = useState(false);
  // Recomputed each poll so running-run durations tick up between fetches.
  const [now, setNow] = useState(() => Date.now());

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
      const response = await client.listRuns(
        {
          profileId: profileFilter.trim(),
          workerId: workerFilter.trim(),
          liveOnly: !includeFinished,
        },
        { headers }
      );
      setRuns(response.runs);
      setNow(Date.now());
      setError(null);
    } catch (err: unknown) {
      handleError(err);
    }
  }, [headers, handleError, profileFilter, workerFilter, includeFinished]);

  useEffect(() => {
    void refresh();
    const interval = setInterval(() => void refresh(), POLL_INTERVAL_MS);
    return () => clearInterval(interval);
  }, [refresh]);

  return (
    <Stack gap="md" p="md">
      <Group justify="space-between" align="flex-end">
        <Title order={3}>Runs</Title>
        <Group gap="sm" align="flex-end">
          <TextInput
            label="Profile"
            placeholder="all profiles"
            value={profileFilter}
            onChange={(e) => setProfileFilter(e.currentTarget.value)}
          />
          <TextInput
            label="Worker id"
            placeholder="all workers"
            value={workerFilter}
            onChange={(e) => setWorkerFilter(e.currentTarget.value)}
          />
          <Checkbox
            label="Include finished"
            checked={includeFinished}
            onChange={(e) => setIncludeFinished(e.currentTarget.checked)}
          />
        </Group>
      </Group>

      {error !== null && (
        <Alert color="red" title="Couldn't load runs">
          {error}
        </Alert>
      )}

      {runs === null && error === null ? (
        <Group justify="center" p="xl">
          <Loader />
        </Group>
      ) : runs !== null && runs.length === 0 ? (
        <Text c="dimmed">
          {includeFinished
            ? 'No runs.'
            : 'No in-flight runs. Toggle "Include finished" for history.'}
        </Text>
      ) : (
        <Table.ScrollContainer minWidth={860}>
          <Table striped highlightOnHover withTableBorder>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Run</Table.Th>
                <Table.Th>Kind</Table.Th>
                <Table.Th>Profile@rev</Table.Th>
                <Table.Th>Worker</Table.Th>
                <Table.Th>State</Table.Th>
                <Table.Th>Started</Table.Th>
                <Table.Th>Duration</Table.Th>
                <Table.Th>Exit</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {(runs ?? []).map((run) => (
                <Table.Tr key={run.runId}>
                  <Table.Td>
                    <Text ff="monospace" size="sm" title={run.runId}>
                      {run.runId.slice(0, 8)}…
                    </Text>
                  </Table.Td>
                  <Table.Td>{runKindLabel(run.kind)}</Table.Td>
                  <Table.Td>{run.profileId ? `${run.profileId}@${run.revision}` : '—'}</Table.Td>
                  <Table.Td>{run.workerName || run.workerId}</Table.Td>
                  <Table.Td>
                    <RunStateBadge state={run.state} />
                  </Table.Td>
                  <Table.Td>{formatDate(run.startedAt)}</Table.Td>
                  <Table.Td>{formatDuration(run, now)}</Table.Td>
                  <Table.Td>{run.hasExitCode ? String(run.exitCode) : '—'}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}
