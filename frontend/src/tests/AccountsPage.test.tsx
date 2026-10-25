import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { AccountsPage } from '../pages/AccountsPage';
import { api } from '../api/client';
import { AccountResponse, LedgerEntryResponse, PagedResponse, TransferResponse } from '../types/api';

vi.mock('../api/client', () => {
  return {
    api: {
      listAccounts: vi.fn(),
      getAccount: vi.fn(),
      getAccountTransfers: vi.fn(),
      getAccountLedger: vi.fn(),
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

describe('AccountsPage', () => {
  const mockAccount1: AccountResponse = {
    id: '11111111-1111-1111-1111-111111111111',
    displayName: 'Treasury Primary Account',
    currency: 'USD',
    openingBalance: '50000.00',
    currentBalance: '48500.00',
    status: 'ACTIVE',
    createdAt: '2026-10-25T08:00:00Z',
    updatedAt: '2026-10-25T10:00:00Z',
  };

  const mockAccount2: AccountResponse = {
    id: '22222222-2222-2222-2222-222222222222',
    displayName: 'Merchant Settlement Account',
    currency: 'USD',
    openingBalance: '1000.00',
    currentBalance: '2500.00',
    status: 'ACTIVE',
    createdAt: '2026-10-25T08:00:00Z',
    updatedAt: '2026-10-25T10:00:00Z',
  };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('renders accounts list with formatted decimal balances', async () => {
    const mockPaged: PagedResponse<AccountResponse> = {
      items: [mockAccount1, mockAccount2],
      page: 0,
      size: 10,
      totalItems: 2,
      totalPages: 1,
    };
    (api.listAccounts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPaged);

    render(<AccountsPage />);

    await waitFor(() => {
      expect(screen.getByText('Treasury Primary Account')).toBeInTheDocument();
      expect(screen.getByText('Merchant Settlement Account')).toBeInTheDocument();
      // Verify decimal strings rendered
      expect(screen.getByText('48,500.00 USD')).toBeInTheDocument();
      expect(screen.getByText('2,500.00 USD')).toBeInTheDocument();
    });
  });

  it('strictly omits public account creation controls', async () => {
    const mockPaged: PagedResponse<AccountResponse> = {
      items: [mockAccount1],
      page: 0,
      size: 10,
      totalItems: 1,
      totalPages: 1,
    };
    (api.listAccounts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPaged);

    render(<AccountsPage />);

    await waitFor(() => {
      expect(screen.getByText('Treasury Primary Account')).toBeInTheDocument();
    });

    // Verify there are no "Create Account", "New Account", or "+ Account" buttons
    expect(screen.queryByRole('button', { name: /create account/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /new account/i })).not.toBeInTheDocument();
    expect(screen.queryByText(/\+ Create/i)).not.toBeInTheDocument();
  });

  it('opens account detail view and displays transfers and double-entry ledger history', async () => {
    const mockPaged: PagedResponse<AccountResponse> = {
      items: [mockAccount1],
      page: 0,
      size: 10,
      totalItems: 1,
      totalPages: 1,
    };
    (api.listAccounts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockPaged);

    const mockTransfer: TransferResponse = {
      id: 'tx-12345',
      sourceAccountId: mockAccount1.id,
      destinationAccountId: mockAccount2.id,
      amount: '1500.00',
      currency: 'USD',
      status: 'SUCCEEDED',
      createdAt: '2026-10-25T09:00:00Z',
      updatedAt: '2026-10-25T09:00:01Z',
      processingAttemptCount: 1,
      correlationId: 'corr-acct-1',
    };
    const mockTransferPaged: PagedResponse<TransferResponse> = {
      items: [mockTransfer],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    };
    (api.getAccountTransfers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockTransferPaged);

    const mockLedger: LedgerEntryResponse = {
      id: 'leg-1',
      transferId: 'tx-12345',
      accountId: mockAccount1.id,
      entryType: 'DEBIT',
      amount: '1500.00',
      currency: 'USD',
      postedAt: '2026-10-25T09:00:01Z',
    };
    const mockLedgerPaged: PagedResponse<LedgerEntryResponse> = {
      items: [mockLedger],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    };
    (api.getAccountLedger as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(mockLedgerPaged);

    render(<AccountsPage />);

    await waitFor(() => {
      expect(screen.getByText('Treasury Primary Account')).toBeInTheDocument();
    });

    // Click "Inspect account"
    fireEvent.click(screen.getByRole('button', { name: /inspect account/i }));

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 3, name: 'Treasury Primary Account' })).toBeInTheDocument();
      expect(screen.getByText('1,500.00 USD')).toBeInTheDocument();
    });

    // Switch to Ledger tab
    fireEvent.click(screen.getByRole('button', { name: /immutable ledger rows/i }));

    await waitFor(() => {
      expect(screen.getByText('DEBIT')).toBeInTheDocument();
      expect(screen.getByText(/leg-1/i)).toBeInTheDocument();
    });
  });
});
