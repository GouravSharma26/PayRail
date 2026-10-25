import React, { useEffect, useState } from 'react';
import {
  ArrowDownLeft,
  ArrowUpRight,
  BookOpen,
  History,
  RefreshCw,
  Search,
  Users,
  X,
} from 'lucide-react';
import { api } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import { Pagination } from '../components/Pagination';
import { TableSkeleton } from '../components/Skeleton';
import { ErrorAlert } from '../components/ErrorAlert';
import { EmptyState } from '../components/EmptyState';
import { AccountResponse, LedgerEntryResponse, TransferResponse } from '../types/api';
import { formatCurrency, formatTimestamp, truncateUuid } from '../utils/formatters';

interface AccountsPageProps {
  onInspectTransfer?: (transferId: string) => void;
}

export const AccountsPage: React.FC<AccountsPageProps> = ({ onInspectTransfer }) => {
  const [accounts, setAccounts] = useState<AccountResponse[]>([]);
  const [page, setPage] = useState(0);
  const [size] = useState(10);
  const [totalItems, setTotalItems] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  // Filters
  const [statusFilter, setStatusFilter] = useState<string>('');
  const [currencyFilter, setCurrencyFilter] = useState<string>('');
  const [searchQuery, setSearchQuery] = useState<string>('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  // Selected account detail modal
  const [selectedAccount, setSelectedAccount] = useState<AccountResponse | null>(null);
  const [activeDetailTab, setActiveDetailTab] = useState<'transfers' | 'ledger'>('transfers');

  // Account transfers state
  const [accountTransfers, setAccountTransfers] = useState<TransferResponse[]>([]);
  const [transfersPage, setTransfersPage] = useState(0);
  const [transfersTotal, setTransfersTotal] = useState(0);
  const [transfersTotalPages, setTransfersTotalPages] = useState(0);
  const [transfersLoading, setTransfersLoading] = useState(false);

  // Account ledger state
  const [accountLedger, setAccountLedger] = useState<LedgerEntryResponse[]>([]);
  const [ledgerPage, setLedgerPage] = useState(0);
  const [ledgerTotal, setLedgerTotal] = useState(0);
  const [ledgerTotalPages, setLedgerTotalPages] = useState(0);
  const [ledgerLoading, setLedgerLoading] = useState(false);

  const loadAccounts = async (signal?: AbortSignal) => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.listAccounts(
        page,
        size,
        statusFilter || undefined,
        currencyFilter || undefined,
        signal
      );
      setAccounts(res.items);
      setTotalItems(res.totalItems);
      setTotalPages(res.totalPages);
    } catch (err: unknown) {
      if (!signal?.aborted) {
        setError(err instanceof Error ? err : new Error(String(err)));
      }
    } finally {
      if (!signal?.aborted) {
        setLoading(false);
      }
    }
  };

  useEffect(() => {
    const controller = new AbortController();
    loadAccounts(controller.signal);
    return () => controller.abort();
  }, [page, statusFilter, currencyFilter]);

  // Load account transfers when selected
  useEffect(() => {
    if (!selectedAccount) return;

    const controller = new AbortController();
    setTransfersLoading(true);

    api
      .getAccountTransfers(selectedAccount.id, transfersPage, 10, controller.signal)
      .then((res) => {
        setAccountTransfers(res.items);
        setTransfersTotal(res.totalItems);
        setTransfersTotalPages(res.totalPages);
      })
      .catch((err) => {
        if (!controller.signal.aborted) {
          console.error('Failed to load account transfers:', err);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setTransfersLoading(false);
        }
      });

    return () => controller.abort();
  }, [selectedAccount, transfersPage]);

  // Load account ledger when selected
  useEffect(() => {
    if (!selectedAccount) return;

    const controller = new AbortController();
    setLedgerLoading(true);

    api
      .getAccountLedger(selectedAccount.id, ledgerPage, 10, controller.signal)
      .then((res) => {
        setAccountLedger(res.items);
        setLedgerTotal(res.totalItems);
        setLedgerTotalPages(res.totalPages);
      })
      .catch((err) => {
        if (!controller.signal.aborted) {
          console.error('Failed to load account ledger:', err);
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) {
          setLedgerLoading(false);
        }
      });

    return () => controller.abort();
  }, [selectedAccount, ledgerPage]);

  const filteredAccounts = searchQuery
    ? accounts.filter(
        (a) =>
          a.displayName.toLowerCase().includes(searchQuery.toLowerCase()) ||
          a.id.toLowerCase().includes(searchQuery.toLowerCase())
      )
    : accounts;

  return (
    <div>
      <div className="page-header">
        <div>
          <h2 className="page-title">Synthetic Accounts</h2>
          <div className="page-description">
            Authoritative accounts with immutable opening balances and ledger-projected current balances.
          </div>
        </div>

        <button
          className="btn btn-secondary"
          onClick={() => loadAccounts()}
          disabled={loading}
          aria-label="Refresh accounts"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          <span>Refresh</span>
        </button>
      </div>

      {error && <ErrorAlert error={error} onRetry={() => loadAccounts()} />}

      <div className="table-wrapper">
        {/* Filter Toolbar */}
        <div className="filter-bar">
          <div className="filter-group">
            <Search size={16} color="var(--text-muted)" />
            <input
              type="text"
              className="input"
              placeholder="Filter by name or UUID..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              style={{ width: '240px' }}
              aria-label="Search accounts by name or ID"
            />
          </div>

          <div className="filter-group">
            <label htmlFor="account-status-filter" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Status:</label>
            <select
              id="account-status-filter"
              className="select"
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All Statuses</option>
              <option value="ACTIVE">ACTIVE</option>
              <option value="INACTIVE">INACTIVE</option>
            </select>
          </div>

          <div className="filter-group">
            <label htmlFor="account-currency-filter" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Currency:</label>
            <select
              id="account-currency-filter"
              className="select"
              value={currencyFilter}
              onChange={(e) => {
                setCurrencyFilter(e.target.value);
                setPage(0);
              }}
            >
              <option value="">All Currencies</option>
              <option value="INR">INR</option>
              <option value="USD">USD</option>
              <option value="EUR">EUR</option>
              <option value="GBP">GBP</option>
            </select>
          </div>
        </div>

        {/* Accounts Table */}
        <div className="table-responsive">
          <table className="table" aria-label="Synthetic Accounts Table">
            <thead>
              <tr>
                <th>Display Name</th>
                <th>Account ID (UUID)</th>
                <th>Currency</th>
                <th>Status</th>
                <th>Opening Balance</th>
                <th>Current Balance</th>
                <th>Created (UTC)</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <TableSkeleton rows={size} columns={8} />
              ) : filteredAccounts.length === 0 ? (
                <tr>
                  <td colSpan={8}>
                    <EmptyState
                      icon={<Users size={32} color="var(--text-muted)" />}
                      title="No accounts found"
                      message="No synthetic accounts match your search or filter criteria."
                    />
                  </td>
                </tr>
              ) : (
                filteredAccounts.map((account) => (
                  <tr key={account.id}>
                    <td style={{ fontWeight: 600 }}>{account.displayName}</td>
                    <td className="mono-cell" title={account.id}>
                      {truncateUuid(account.id)}
                    </td>
                    <td className="mono-cell">{account.currency}</td>
                    <td>
                      <StatusBadge status={account.status} />
                    </td>
                    <td className="mono-cell" style={{ color: 'var(--text-muted)' }}>
                      {formatCurrency(account.openingBalance, account.currency)}
                    </td>
                    <td className="mono-cell" style={{ fontWeight: 700, color: 'var(--accent-cyan)' }}>
                      {formatCurrency(account.currentBalance, account.currency)}
                    </td>
                    <td className="mono-cell">{formatTimestamp(account.createdAt)}</td>
                    <td>
                      <button
                        className="btn btn-secondary"
                        style={{ padding: '3px 8px', fontSize: '0.75rem' }}
                        onClick={() => {
                          setSelectedAccount(account);
                          setTransfersPage(0);
                          setLedgerPage(0);
                          setActiveDetailTab('transfers');
                        }}
                        aria-label={`Inspect account ${account.displayName}`}
                      >
                        Details
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>

        {/* Pagination */}
        <Pagination
          page={page}
          size={size}
          totalItems={totalItems}
          totalPages={totalPages}
          onPageChange={setPage}
          disabled={loading}
        />
      </div>

      {/* Account Detail Modal */}
      {selectedAccount && (
        <div
          className="modal-overlay"
          role="dialog"
          aria-modal="true"
          aria-labelledby="account-modal-title"
          onClick={(e) => {
            if (e.target === e.currentTarget) setSelectedAccount(null);
          }}
        >
          <div className="modal">
            <div className="modal-header">
              <div>
                <h3 id="account-modal-title" style={{ fontSize: '1.15rem', fontWeight: 700 }}>
                  {selectedAccount.displayName}
                </h3>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                  ID: {selectedAccount.id}
                </div>
              </div>
              <button
                className="btn-icon"
                onClick={() => setSelectedAccount(null)}
                aria-label="Close dialog"
              >
                <X size={18} />
              </button>
            </div>

            <div className="modal-body">
              {/* Account Metadata Grid */}
              <div className="detail-grid">
                <div className="detail-item">
                  <span className="detail-label">Status</span>
                  <span className="detail-value">
                    <StatusBadge status={selectedAccount.status} />
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Currency</span>
                  <span className="detail-value font-mono">{selectedAccount.currency}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Opening Balance</span>
                  <span className="detail-value font-mono">
                    {formatCurrency(selectedAccount.openingBalance, selectedAccount.currency)}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Current Balance</span>
                  <span
                    className="detail-value font-mono"
                    style={{ color: 'var(--accent-cyan)', fontWeight: 700 }}
                  >
                    {formatCurrency(selectedAccount.currentBalance, selectedAccount.currency)}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Created At</span>
                  <span className="detail-value font-mono">{formatTimestamp(selectedAccount.createdAt)}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Updated At</span>
                  <span className="detail-value font-mono">{formatTimestamp(selectedAccount.updatedAt)}</span>
                </div>
              </div>

              {/* Tabs for Transfer History vs Double-Entry Ledger */}
              <div style={{ display: 'flex', gap: '8px', borderBottom: '1px solid var(--border-color)', marginBottom: '16px' }}>
                <button
                  className={`btn ${activeDetailTab === 'transfers' ? 'btn-primary' : 'btn-secondary'}`}
                  style={{ borderRadius: '6px 6px 0 0', fontSize: '0.825rem' }}
                  onClick={() => setActiveDetailTab('transfers')}
                >
                  <History size={14} />
                  <span>Transfer History ({transfersTotal})</span>
                </button>
                <button
                  className={`btn ${activeDetailTab === 'ledger' ? 'btn-primary' : 'btn-secondary'}`}
                  style={{ borderRadius: '6px 6px 0 0', fontSize: '0.825rem' }}
                  onClick={() => setActiveDetailTab('ledger')}
                >
                  <BookOpen size={14} />
                  <span>Immutable Ledger Rows ({ledgerTotal})</span>
                </button>
              </div>

              {/* Tab 1: Transfers for Account */}
              {activeDetailTab === 'transfers' && (
                <div>
                  <div className="table-responsive">
                    <table className="table" aria-label="Account Transfer History">
                      <thead>
                        <tr>
                          <th>Transfer ID</th>
                          <th>Status</th>
                          <th>Amount</th>
                          <th>Direction</th>
                          <th>Counterparty</th>
                          <th>Date</th>
                          {onInspectTransfer && <th>Action</th>}
                        </tr>
                      </thead>
                      <tbody>
                        {transfersLoading ? (
                          <TableSkeleton rows={4} columns={onInspectTransfer ? 7 : 6} />
                        ) : accountTransfers.length === 0 ? (
                          <tr>
                            <td colSpan={onInspectTransfer ? 7 : 6} style={{ textAlign: 'center', padding: '24px', color: 'var(--text-muted)' }}>
                              No transfers found for this account.
                            </td>
                          </tr>
                        ) : (
                          accountTransfers.map((tx) => {
                            const isOutbound = tx.sourceAccountId === selectedAccount.id;
                            const counterparty = isOutbound ? tx.destinationAccountId : tx.sourceAccountId;
                            return (
                              <tr key={tx.id}>
                                <td className="mono-cell" title={tx.id}>{truncateUuid(tx.id)}</td>
                                <td><StatusBadge status={tx.status} /></td>
                                <td className="mono-cell" style={{ fontWeight: 600 }}>
                                  {formatCurrency(tx.amount, tx.currency)}
                                </td>
                                <td>
                                  {isOutbound ? (
                                    <span style={{ color: 'var(--status-danger)', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                      <ArrowUpRight size={14} /> Outbound
                                    </span>
                                  ) : (
                                    <span style={{ color: 'var(--status-success)', display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                                      <ArrowDownLeft size={14} /> Inbound
                                    </span>
                                  )}
                                </td>
                                <td className="mono-cell" title={counterparty}>{truncateUuid(counterparty)}</td>
                                <td className="mono-cell">{formatTimestamp(tx.createdAt)}</td>
                                {onInspectTransfer && (
                                  <td>
                                    <button
                                      className="btn btn-secondary"
                                      style={{ padding: '2px 6px', fontSize: '0.7rem' }}
                                      onClick={() => {
                                        setSelectedAccount(null);
                                        onInspectTransfer(tx.id);
                                      }}
                                    >
                                      Inspect
                                    </button>
                                  </td>
                                )}
                              </tr>
                            );
                          })
                        )}
                      </tbody>
                    </table>
                  </div>

                  <Pagination
                    page={transfersPage}
                    size={10}
                    totalItems={transfersTotal}
                    totalPages={transfersTotalPages}
                    onPageChange={setTransfersPage}
                    disabled={transfersLoading}
                  />
                </div>
              )}

              {/* Tab 2: Ledger Rows for Account */}
              {activeDetailTab === 'ledger' && (
                <div>
                  <div className="table-responsive">
                    <table className="table" aria-label="Account Ledger Entries">
                      <thead>
                        <tr>
                          <th>Entry ID</th>
                          <th>Transfer ID</th>
                          <th>Type</th>
                          <th>Amount</th>
                          <th>Posted (UTC)</th>
                        </tr>
                      </thead>
                      <tbody>
                        {ledgerLoading ? (
                          <TableSkeleton rows={4} columns={5} />
                        ) : accountLedger.length === 0 ? (
                          <tr>
                            <td colSpan={5} style={{ textAlign: 'center', padding: '24px', color: 'var(--text-muted)' }}>
                              No double-entry ledger rows posted for this account yet.
                            </td>
                          </tr>
                        ) : (
                          accountLedger.map((entry) => (
                            <tr key={entry.id}>
                              <td className="mono-cell" title={entry.id}>{truncateUuid(entry.id)}</td>
                              <td className="mono-cell" title={entry.transferId}>
                                {onInspectTransfer ? (
                                  <button
                                    onClick={() => {
                                      setSelectedAccount(null);
                                      onInspectTransfer(entry.transferId);
                                    }}
                                    style={{ color: 'var(--accent-cyan)', textDecoration: 'underline' }}
                                  >
                                    {truncateUuid(entry.transferId)}
                                  </button>
                                ) : (
                                  truncateUuid(entry.transferId)
                                )}
                              </td>
                              <td>
                                <span
                                  className={`badge ${entry.entryType === 'CREDIT' ? 'badge-success' : 'badge-warning'}`}
                                >
                                  {entry.entryType}
                                </span>
                              </td>
                              <td className="mono-cell" style={{ fontWeight: 600 }}>
                                {formatCurrency(entry.amount, entry.currency)}
                              </td>
                              <td className="mono-cell">{formatTimestamp(entry.postedAt)}</td>
                            </tr>
                          ))
                        )}
                      </tbody>
                    </table>
                  </div>

                  <Pagination
                    page={ledgerPage}
                    size={10}
                    totalItems={ledgerTotal}
                    totalPages={ledgerTotalPages}
                    onPageChange={setLedgerPage}
                    disabled={ledgerLoading}
                  />
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
