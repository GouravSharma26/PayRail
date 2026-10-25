import { render, screen, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { OperationsPage } from '../pages/OperationsPage';
import { api } from '../api/client';
import {
  ActuatorHealthResponse,
  OutboxStatusResponse,
  SystemHealthSummaryResponse,
} from '../types/api';

vi.mock('../api/client', () => {
  return {
    api: {
      getHealthSummary: vi.fn(),
      getOutboxStatus: vi.fn(),
      getActuatorHealth: vi.fn(),
      getPrometheusMetrics: vi.fn(),
    },
    ApiError: class ApiError extends Error {
      status: number;
      code: string;
      correlationId?: string;
      constructor(status: number, code: string, message: string, correlationId?: string) {
        super(message);
        this.status = status;
        this.code = code;
        this.correlationId = correlationId;
      }
    },
  };
});

describe('OperationsPage', () => {
  const mockHealthSummary: SystemHealthSummaryResponse = {
    status: 'UP',
    dbStatus: 'UP',
    kafkaStatus: 'UP',
    outboxBacklog: 2,
    lastReconciliationStatus: 'CLEAN',
    lastReconciliationTime: '2026-10-25T11:45:00Z',
    openReconciliationIssues: 0,
    timestamp: '2026-10-25T12:00:00Z',
  };

  const mockOutboxStatus: OutboxStatusResponse = {
    pendingCount: 2,
    failedCount: 1,
    exhaustedCount: 0,
    totalUnpublished: 3,
    oldestUnpublishedAgeSeconds: 15,
    publisherEnabled: true,
  };

  const mockActuator: ActuatorHealthResponse = {
    status: 'UP',
    components: {
      db: { status: 'UP', details: { database: 'PostgreSQL' } },
      diskSpace: { status: 'UP' },
      ping: { status: 'UP' },
    },
  };

  const mockPrometheus = `
# HELP payrail_transfers_submitted_total Total submitted
payrail_transfers_submitted_total 42
# HELP payrail_consumer_events_received_total Consumer received
payrail_consumer_events_received_total 80
# HELP payrail_consumer_dlq_routed_total DLQ count
payrail_consumer_dlq_routed_total 0
`;

  beforeEach(() => {
    vi.clearAllMocks();
    (api.getHealthSummary as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockHealthSummary);
    (api.getOutboxStatus as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockOutboxStatus);
    (api.getActuatorHealth as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockActuator);
    (api.getPrometheusMetrics as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPrometheus);
  });

  it('renders outbox backlog telemetry and system health cards', async () => {
    render(<OperationsPage />);

    await waitFor(() => {
      expect(screen.getByText(/Transactional Outbox Telemetry/i)).toBeInTheDocument();
      expect(screen.getByText('Publisher Active')).toBeInTheDocument();
      expect(screen.getByText('PostgreSQL Database')).toBeInTheDocument();
      expect(screen.getByText('Kafka Message Broker')).toBeInTheDocument();
      expect(screen.getByText('15s')).toBeInTheDocument(); // oldest event age
    });
  });

  it('renders read-only diagnostics notice and omits unsafe replay buttons', async () => {
    render(<OperationsPage />);

    await waitFor(() => {
      expect(screen.getByText(/Read-Only Operational Diagnostics Notice/i)).toBeInTheDocument();
    });

    // Invariant: no replay controls without explicit backend authorization
    expect(screen.queryByRole('button', { name: /replay/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /reprocess dlq/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /retry failed outbox/i })).not.toBeInTheDocument();
  });

  it('displays consumer and DLQ summary telemetry', async () => {
    render(<OperationsPage />);

    await waitFor(() => {
      expect(screen.getByText(/Event Consumer & Dead-Letter Queue/i)).toBeInTheDocument();
      expect(screen.getByText('80')).toBeInTheDocument(); // consumer received
      expect(screen.getByText(/DLQ Routed Events/i)).toBeInTheDocument();
    });
  });
});
