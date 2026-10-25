import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { App } from '../App';
import { api } from '../api/client';
import { SystemHealthSummaryResponse } from '../types/api';

vi.mock('../api/client', () => {
  return {
    api: {
      getHealthSummary: vi.fn(),
      getOutboxStatus: vi.fn(),
      getActuatorHealth: vi.fn(),
      getPrometheusMetrics: vi.fn(),
      listAccounts: vi.fn(),
      searchTransfers: vi.fn(),
      listReconciliationRuns: vi.fn(),
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

describe('Layout and Application Navigation', () => {
  const mockHealth: SystemHealthSummaryResponse = {
    status: 'UP',
    dbStatus: 'UP',
    kafkaStatus: 'UP',
    outboxBacklog: 0,
    lastReconciliationStatus: 'CLEAN',
    lastReconciliationTime: '2026-10-25T12:00:00Z',
    openReconciliationIssues: 0,
    timestamp: '2026-10-25T12:00:00Z',
  };

  beforeEach(() => {
    vi.clearAllMocks();
    (api.getHealthSummary as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockHealth);
    (api.getOutboxStatus as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      pendingCount: 0,
      failedCount: 0,
      exhaustedCount: 0,
      totalUnpublished: 0,
      oldestUnpublishedAgeSeconds: null,
      publisherEnabled: true,
    });
    (api.listAccounts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      items: [],
      page: 0,
      size: 10,
      totalItems: 0,
      totalPages: 0,
    });
    (api.searchTransfers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      items: [],
      page: 0,
      size: 10,
      totalItems: 0,
      totalPages: 0,
    });
    (api.listReconciliationRuns as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (api.getPrometheusMetrics as unknown as ReturnType<typeof vi.fn>).mockResolvedValue('');
    (api.getActuatorHealth as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ status: 'UP' });
  });

  it('renders the persistent synthetic simulator warning on Overview route', async () => {
    render(<App />);

    const warningBanner = screen.getByRole('banner', { name: /simulator warning/i });
    expect(warningBanner).toBeInTheDocument();
    expect(
      screen.getByText(/PayRail Synthetic Payment Simulator — no real money or bank connections\./i)
    ).toBeInTheDocument();
  });

  it('preserves the simulator warning across all route transitions', async () => {
    render(<App />);

    const warningText = /PayRail Synthetic Payment Simulator — no real money or bank connections\./i;
    expect(screen.getByText(warningText)).toBeInTheDocument();

    // Navigate to Accounts
    fireEvent.click(screen.getByRole('button', { name: /accounts/i }));
    expect(screen.getByText(warningText)).toBeInTheDocument();
    expect(screen.getByText(/Synthetic Accounts/i)).toBeInTheDocument();

    // Navigate to Transfers
    fireEvent.click(screen.getByRole('button', { name: /transfers/i }));
    expect(screen.getByText(warningText)).toBeInTheDocument();
    expect(screen.getByText(/Simulated Transfers/i)).toBeInTheDocument();

    // Navigate to Reconciliation
    fireEvent.click(screen.getByRole('button', { name: /reconciliation/i }));
    expect(screen.getByText(warningText)).toBeInTheDocument();
    expect(screen.getByText(/Continuous Accounting Reconciliation/i)).toBeInTheDocument();

    // Navigate to Operations
    fireEvent.click(screen.getByRole('button', { name: /operations/i }));
    expect(screen.getByText(warningText)).toBeInTheDocument();
    expect(screen.getByText(/Operations & Health Diagnostics/i)).toBeInTheDocument();
  });

  it('displays system health status indicator in the header', async () => {
    render(<App />);

    await waitFor(() => {
      expect(screen.getByText(/SYSTEM UP/i)).toBeInTheDocument();
    });
  });
});
