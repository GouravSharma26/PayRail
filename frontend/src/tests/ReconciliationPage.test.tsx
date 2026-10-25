import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ReconciliationPage } from '../pages/ReconciliationPage';
import { api, ApiError } from '../api/client';
import {
  ReconciliationRunDetailResponse,
  ReconciliationRunRecord,
} from '../types/api';

vi.mock('../api/client', () => {
  return {
    api: {
      listReconciliationRuns: vi.fn(),
      getReconciliationRunDetail: vi.fn(),
      triggerReconciliation: vi.fn(),
    },
    ApiError: class ApiError extends Error {
      status: number;
      code: string;
      correlationId?: string;
      retryAfterSeconds?: number;
      constructor(status: number, code: string, message: string, correlationId?: string, retryAfterSeconds?: number) {
        super(message);
        this.status = status;
        this.code = code;
        this.correlationId = correlationId;
        this.retryAfterSeconds = retryAfterSeconds;
      }
    },
  };
});

describe('ReconciliationPage', () => {
  const mockCleanRun: ReconciliationRunRecord = {
    id: 'run-clean-01',
    startedAt: '2026-10-25T11:00:00Z',
    finishedAt: '2026-10-25T11:00:02Z',
    status: 'CLEAN',
    scannedTransfersCount: 150,
    scannedAccountsCount: 25,
    issueCount: 0,
    triggerType: 'SCHEDULED',
    summary: 'Audit completed successfully. All ledger invariants held.',
  };

  const mockDiscrepancyRun: ReconciliationRunRecord = {
    id: 'run-disc-02',
    startedAt: '2026-10-25T11:30:00Z',
    finishedAt: '2026-10-25T11:30:03Z',
    status: 'DISCREPANCIES_DETECTED',
    scannedTransfersCount: 200,
    scannedAccountsCount: 30,
    issueCount: 1,
    triggerType: 'MANUAL',
    summary: 'Discrepancy detected during balance invariant verification.',
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders reconciliation runs and detail inspection', async () => {
    (api.listReconciliationRuns as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      mockCleanRun,
      mockDiscrepancyRun,
    ]);

    const mockDetail: ReconciliationRunDetailResponse = {
      run: mockDiscrepancyRun,
      issues: [
        {
          id: 'issue-001',
          runId: 'run-disc-02',
          issueType: 'BALANCE_MISMATCH',
          severity: 'HIGH',
          accountId: 'acct-999',
          detectedAt: '2026-10-25T11:30:02Z',
          detailsJson: '{"expected": "1000.00", "actual": "950.00"}',
          status: 'OPEN',
        },
      ],
    };
    (api.getReconciliationRunDetail as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockDetail);

    render(<ReconciliationPage />);

    await waitFor(() => {
      expect(screen.getByText('CLEAN')).toBeInTheDocument();
      expect(screen.getByText('DISCREPANCIES_DETECTED')).toBeInTheDocument();
    });

    // Inspect run with discrepancies
    const inspectButtons = screen.getAllByRole('button', { name: /inspect/i });
    fireEvent.click(inspectButtons[1]);

    await waitFor(() => {
      expect(screen.getByText(/Audit Run Execution Details/i)).toBeInTheDocument();
      expect(screen.getByText('BALANCE_MISMATCH')).toBeInTheDocument();
      expect(screen.getByText(/expected": "1000.00"/i)).toBeInTheDocument();
    });
  });

  it('strictly omits automatic repair or direct ledger mutation controls', async () => {
    (api.listReconciliationRuns as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([mockDiscrepancyRun]);

    render(<ReconciliationPage />);

    await waitFor(() => {
      expect(screen.getByText('DISCREPANCIES_DETECTED')).toBeInTheDocument();
    });

    // Invariant: Reconciliation detects, it does NOT silently repair or modify ledger
    expect(screen.queryByRole('button', { name: /auto-repair/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /fix balance/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /edit ledger/i })).not.toBeInTheDocument();
  });

  it('handles 429 rate limit / cooldown gracefully on manual audit trigger', async () => {
    (api.listReconciliationRuns as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([mockCleanRun]);
    const cooldownError = new ApiError(
      429,
      'AUDIT_COOLDOWN_ACTIVE',
      'Audit was executed recently. Please wait before triggering another audit.',
      'corr-cd-1',
      30
    );
    (api.triggerReconciliation as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(cooldownError);

    render(<ReconciliationPage />);

    await waitFor(() => {
      expect(screen.getByText('CLEAN')).toBeInTheDocument();
    });

    const triggerBtn = screen.getByRole('button', { name: /trigger manual reconciliation audit/i });
    fireEvent.click(triggerBtn);

    await waitFor(() => {
      expect(screen.getByText(/Reconciliation cooldown active/i)).toBeInTheDocument();
    });
  });
});
