import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { TransfersPage } from '../pages/TransfersPage';
import { api, ApiError } from '../api/client';
import {
  PagedResponse,
  ProcessingAttemptResponse,
  TransferResponse,
  TransferStatusHistoryResponse,
} from '../types/api';

vi.mock('../api/client', () => {
  return {
    api: {
      searchTransfers: vi.fn(),
      getTransfer: vi.fn(),
      getTransferHistory: vi.fn(),
      getTransferAttempts: vi.fn(),
      getTransferLedger: vi.fn(),
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

describe('TransfersPage', () => {
  const mockSucceededTransfer: TransferResponse = {
    id: '11111111-2222-3333-4444-555555555555',
    sourceAccountId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    destinationAccountId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    amount: '250.00',
    currency: 'USD',
    status: 'SUCCEEDED',
    createdAt: '2026-10-25T10:00:00Z',
    updatedAt: '2026-10-25T10:00:02Z',
    completedAt: '2026-10-25T10:00:02Z',
    processingAttemptCount: 1,
    correlationId: 'corr-12345',
  };

  const mockPendingTransfer: TransferResponse = {
    id: '99999999-8888-7777-6666-555555555555',
    sourceAccountId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    destinationAccountId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    amount: '120.50',
    currency: 'USD',
    status: 'PENDING',
    createdAt: '2026-10-25T10:05:00Z',
    updatedAt: '2026-10-25T10:05:00Z',
    processingAttemptCount: 0,
    correlationId: 'corr-pending-99',
  };

  const mockFailedTransfer: TransferResponse = {
    id: '33333333-4444-5555-6666-777777777777',
    sourceAccountId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    destinationAccountId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    amount: '5000.00',
    currency: 'USD',
    status: 'FAILED',
    failureCode: 'INSUFFICIENT_FUNDS',
    failureMessage: 'Account balance is insufficient for transfer.',
    createdAt: '2026-10-25T10:10:00Z',
    updatedAt: '2026-10-25T10:10:01Z',
    completedAt: '2026-10-25T10:10:01Z',
    processingAttemptCount: 1,
    correlationId: 'corr-failed-33',
  };

  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders transfers and supports status filtering', async () => {
    const mockPaged: PagedResponse<TransferResponse> = {
      items: [mockSucceededTransfer],
      page: 0,
      size: 10,
      totalItems: 1,
      totalPages: 1,
    };
    (api.searchTransfers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPaged);

    render(<TransfersPage />);

    await waitFor(() => {
      expect(screen.getByText('250.00 USD')).toBeInTheDocument();
      expect(screen.getByRole('status', { name: /succeeded/i })).toBeInTheDocument();
    });

    // Test filter change
    const statusSelect = screen.getByRole('combobox', { name: /status/i });
    fireEvent.change(statusSelect, { target: { value: 'FAILED' } });

    await waitFor(() => {
      expect(api.searchTransfers).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'FAILED' }),
        expect.any(AbortSignal)
      );
    });
  });

  it('handles pagination navigation correctly', async () => {
    const mockPage0: PagedResponse<TransferResponse> = {
      items: [mockSucceededTransfer],
      page: 0,
      size: 1,
      totalItems: 2,
      totalPages: 2,
    };
    const mockPage1: PagedResponse<TransferResponse> = {
      items: [mockFailedTransfer],
      page: 1,
      size: 1,
      totalItems: 2,
      totalPages: 2,
    };

    (api.searchTransfers as unknown as ReturnType<typeof vi.fn>).mockImplementation((filter) => {
      if (filter?.page === 1) {
        return Promise.resolve(mockPage1);
      }
      return Promise.resolve(mockPage0);
    });

    render(<TransfersPage />);

    await waitFor(() => {
      expect(screen.getByText('250.00 USD')).toBeInTheDocument();
      expect(screen.getByText((_, el) => el?.tagName.toLowerCase() === 'span' && el?.textContent?.replace(/\s+/g, ' ').trim() === 'Page 1 of 2')).toBeInTheDocument();
    });

    const nextBtn = screen.getByRole('button', { name: /next page/i });
    fireEvent.click(nextBtn);

    await waitFor(() => {
      expect(screen.getByText('5,000.00 USD')).toBeInTheDocument();
      expect(screen.getByText((_, el) => el?.tagName.toLowerCase() === 'span' && el?.textContent?.replace(/\s+/g, ' ').trim() === 'Page 2 of 2')).toBeInTheDocument();
    });
  });

  it('displays empty state when no transfers match filter criteria', async () => {
    const emptyPaged: PagedResponse<TransferResponse> = {
      items: [],
      page: 0,
      size: 10,
      totalItems: 0,
      totalPages: 0,
    };
    (api.searchTransfers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(emptyPaged);

    render(<TransfersPage />);

    await waitFor(() => {
      expect(screen.getByText(/No transfers found/i)).toBeInTheDocument();
    });
  });

  it('renders safe API error state and retry button on failure', async () => {
    const apiError = new ApiError(500, 'INTERNAL_SERVER_ERROR', 'Database unavailable', 'corr-err-999');
    (api.searchTransfers as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(apiError);

    render(<TransfersPage />);

    await waitFor(() => {
      expect(screen.getByText(/Database unavailable/i)).toBeInTheDocument();
      expect(screen.getByText(/INTERNAL_SERVER_ERROR/i)).toBeInTheDocument();
      expect(screen.getByText(/corr-err-999/i)).toBeInTheDocument();
      expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument();
    });
  });

  it('renders transfer details with status timeline, processing attempts, and safe failure info', async () => {
    const mockPaged: PagedResponse<TransferResponse> = {
      items: [mockFailedTransfer],
      page: 0,
      size: 10,
      totalItems: 1,
      totalPages: 1,
    };
    (api.searchTransfers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPaged);
    (api.getTransfer as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockFailedTransfer);

    const mockHistory: TransferStatusHistoryResponse[] = [
      {
        id: 1,
        transferId: mockFailedTransfer.id,
        fromStatus: null,
        toStatus: 'PENDING',
        occurredAt: '2026-10-25T10:10:00Z',
      },
      {
        id: 2,
        transferId: mockFailedTransfer.id,
        fromStatus: 'PENDING',
        toStatus: 'FAILED',
        reasonCode: 'INSUFFICIENT_FUNDS',
        occurredAt: '2026-10-25T10:10:01Z',
      },
    ];
    (api.getTransferHistory as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockHistory);

    const mockAttempts: ProcessingAttemptResponse[] = [
      {
        id: 'attempt-1',
        transferId: mockFailedTransfer.id,
        attemptNumber: 1,
        outcome: 'FAILED',
        errorCode: 'INSUFFICIENT_FUNDS',
        safeErrorMessage: 'Account balance is insufficient for transfer.',
        startedAt: '2026-10-25T10:10:00.100Z',
        finishedAt: '2026-10-25T10:10:01.000Z',
        durationMs: 900,
      },
    ];
    (api.getTransferAttempts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockAttempts);
    (api.getTransferLedger as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([]);

    render(<TransfersPage />);

    await waitFor(() => {
      expect(screen.getByText('5,000.00 USD')).toBeInTheDocument();
    });

    // Click "Inspect transfer"
    const viewBtn = screen.getByRole('button', { name: /inspect transfer/i });
    fireEvent.click(viewBtn);

    await waitFor(() => {
      // Safe failure message rendered
      expect(screen.getByText('Account balance is insufficient for transfer.')).toBeInTheDocument();
      expect(screen.getByText('INSUFFICIENT_FUNDS')).toBeInTheDocument();
      // Status history rendered
      expect(screen.getByText(/Status Transition Timeline/i)).toBeInTheDocument();
      // Processing attempts rendered
      expect(screen.getByText(/Execution Attempts Audit/i)).toBeInTheDocument();
      expect(screen.getByText(/Attempt #1/i)).toBeInTheDocument();
    });
  });

  it('stops polling when a transfer reaches terminal SUCCEEDED status', async () => {
    vi.useFakeTimers();

    const mockPaged: PagedResponse<TransferResponse> = {
      items: [mockPendingTransfer],
      page: 0,
      size: 10,
      totalItems: 1,
      totalPages: 1,
    };
    (api.searchTransfers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPaged);

    // Initial fetch returns PENDING, second poll returns SUCCEEDED
    (api.getTransfer as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValueOnce(mockPendingTransfer)
      .mockResolvedValueOnce(mockSucceededTransfer);

    (api.getTransferHistory as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (api.getTransferAttempts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (api.getTransferLedger as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([]);

    render(<TransfersPage initialTransferId={mockPendingTransfer.id} />);

    // Fast-forward through first render and initial fetch
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });

    // Expect getTransfer called for initial selection
    expect(api.getTransfer).toHaveBeenCalledTimes(1);

    // Advance 2 seconds (poll interval for nonterminal transfer)
    await act(async () => {
      await vi.advanceTimersByTimeAsync(2100);
    });
    expect(api.getTransfer).toHaveBeenCalledTimes(2);

    // Transfer is now SUCCEEDED (terminal), advance another 10 seconds
    await act(async () => {
      await vi.advanceTimersByTimeAsync(10000);
    });

    // Polling must have stopped; no further getTransfer calls
    expect(api.getTransfer).toHaveBeenCalledTimes(2);
  });

  it('cleans up polling timers on component unmount', async () => {
    vi.useFakeTimers();

    const mockPaged: PagedResponse<TransferResponse> = {
      items: [mockPendingTransfer],
      page: 0,
      size: 10,
      totalItems: 1,
      totalPages: 1,
    };
    (api.searchTransfers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPaged);
    (api.getTransfer as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPendingTransfer);
    (api.getTransferHistory as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (api.getTransferAttempts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([]);
    (api.getTransferLedger as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([]);

    const { unmount } = render(<TransfersPage initialTransferId={mockPendingTransfer.id} />);
    await act(async () => {
      await vi.advanceTimersByTimeAsync(100);
    });

    expect(api.getTransfer).toHaveBeenCalledTimes(1);

    // Unmount before next poll interval
    unmount();

    // Advance time past the 2s polling window
    await act(async () => {
      await vi.advanceTimersByTimeAsync(5000);
    });

    // No additional calls after unmount
    expect(api.getTransfer).toHaveBeenCalledTimes(1);
  });
});
