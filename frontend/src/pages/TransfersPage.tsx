import React, { useEffect, useRef, useState } from 'react';
import {
  ArrowDownLeft,
  ArrowLeft,
  ArrowUpRight,
  BookOpen,
  CheckCircle2,
  Clock,
  History,
  Layers,
  RefreshCw,
  Search,
  XCircle,
} from 'lucide-react';
import { api } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import { Pagination } from '../components/Pagination';
import { TableSkeleton } from '../components/Skeleton';
import { ErrorAlert } from '../components/ErrorAlert';
import { EmptyState } from '../components/EmptyState';
import {
  LedgerEntryResponse,
  ProcessingAttemptResponse,
  TransferResponse,
  TransferStatus,
  TransferStatusHistoryResponse,
} from '../types/api';
import { formatCurrency, formatTimestamp, truncateUuid } from '../utils/formatters';

interface TransfersPageProps {
  initialTransferId?: string | null;
  onClearInitialTransferId?: () => void;
}

export const TransfersPage: React.FC<TransfersPageProps> = ({
  initialTransferId,
  onClearInitialTransferId,
}) => {
  const [transfers, setTransfers] = useState<TransferResponse[]>([]);
  const [page, setPage] = useState(0);
  const [size] = useState(10);
  const [totalItems, setTotalItems] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  // Filters
  const [statusFilter, setStatusFilter] = useState<TransferStatus | ''>('');
  const [currencyFilter, setCurrencyFilter] = useState<string>('');
  const [transferIdFilter, setTransferIdFilter] = useState<string>('');
  const [fromDate, setFromDate] = useState<string>('');
  const [toDate, setToDate] = useState<string>('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  // Detail view state
  const [selectedTransfer, setSelectedTransfer] = useState<TransferResponse | null>(null);
  const [history, setHistory] = useState<TransferStatusHistoryResponse[]>([]);
  const [attempts, setAttempts] = useState<ProcessingAttemptResponse[]>([]);
  const [ledgerEntries, setLedgerEntries] = useState<LedgerEntryResponse[]>([]);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<Error | null>(null);

  // Ref to track polling timer for cleanup
  const pollingTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  const loadTransfers = async (signal?: AbortSignal) => {
    setLoading(true);
    setError(null);
    try {
      const res = await api.searchTransfers(
        {
          page,
          size,
          status: statusFilter || undefined,
          currency: currencyFilter || undefined,
          transferId: transferIdFilter || undefined,
          fromDate: fromDate ? new Date(fromDate).toISOString() : undefined,
          toDate: toDate ? new Date(toDate).toISOString() : undefined,
        },
        signal
      );
      setTransfers(res.items);
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
    loadTransfers(controller.signal);
    return () => controller.abort();
  }, [page, statusFilter, currencyFilter, fromDate, toDate]);

  // Load single transfer detail
  const loadTransferDetails = async (id: string, isPoll = false) => {
    if (!isPoll) {
      setDetailLoading(true);
      setDetailError(null);
    }

    try {
      const [tx, hist, att, ledg] = await Promise.all([
        api.getTransfer(id),
        api.getTransferHistory(id),
        api.getTransferAttempts(id),
        api.getTransferLedger(id),
      ]);

      setSelectedTransfer(tx);
      setHistory(hist);
      setAttempts(att);
      setLedgerEntries(ledg);

      // Bounded polling: Poll only if non-terminal (PENDING or PROCESSING)
      const isTerminal = tx.status === 'SUCCEEDED' || tx.status === 'FAILED';
      if (!isTerminal) {
        pollingTimerRef.current = setTimeout(() => {
          loadTransferDetails(id, true);
        }, 2000);
      } else {
        // Stop polling
        if (pollingTimerRef.current) {
          clearTimeout(pollingTimerRef.current);
          pollingTimerRef.current = null;
        }
      }
    } catch (err: unknown) {
      if (!isPoll) {
        setDetailError(err instanceof Error ? err : new Error(String(err)));
      }
    } finally {
      if (!isPoll) {
        setDetailLoading(false);
      }
    }
  };

  // Open detail if initialTransferId was passed
  useEffect(() => {
    if (initialTransferId) {
      loadTransferDetails(initialTransferId);
      if (onClearInitialTransferId) onClearInitialTransferId();
    }
  }, [initialTransferId]);

  // Cleanup polling timer on unmount
  useEffect(() => {
    return () => {
      if (pollingTimerRef.current) {
        clearTimeout(pollingTimerRef.current);
        pollingTimerRef.current = null;
      }
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
    };
  }, []);

  const handleSelectTransfer = (tx: TransferResponse) => {
    if (pollingTimerRef.current) {
      clearTimeout(pollingTimerRef.current);
      pollingTimerRef.current = null;
    }
    loadTransferDetails(tx.id);
  };

  const handleBackToList = () => {
    if (pollingTimerRef.current) {
      clearTimeout(pollingTimerRef.current);
      pollingTimerRef.current = null;
    }
    setSelectedTransfer(null);
    loadTransfers();
  };

  return (
    <div>
      <div className="page-header">
        <div>
          <h2 className="page-title">Simulated Transfers</h2>
          <div className="page-description">
            Idempotent transfer lifecycle from durable acceptance (PENDING) through asynchronous Kafka processing to terminal state.
          </div>
        </div>

        <button
          className="btn btn-secondary"
          onClick={() => {
            if (selectedTransfer) {
              loadTransferDetails(selectedTransfer.id);
            } else {
              loadTransfers();
            }
          }}
          disabled={loading || detailLoading}
          aria-label="Refresh transfers"
        >
          <RefreshCw size={14} className={loading || detailLoading ? 'animate-spin' : ''} />
          <span>Refresh</span>
        </button>
      </div>

      {error && <ErrorAlert error={error} onRetry={() => loadTransfers()} />}

      {/* Main View: List or Detail */}
      {!selectedTransfer ? (
        <div className="table-wrapper">
          {/* Filters Bar */}
          <div className="filter-bar">
            <div className="filter-group">
              <Search size={16} color="var(--text-muted)" />
              <input
                type="text"
                className="input"
                placeholder="Search by exact UUID..."
                value={transferIdFilter}
                onChange={(e) => setTransferIdFilter(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    setPage(0);
                    loadTransfers();
                  }
                }}
                style={{ width: '240px' }}
                aria-label="Filter by transfer ID"
              />
              <button className="btn btn-secondary" onClick={() => { setPage(0); loadTransfers(); }} style={{ padding: '6px 10px' }}>
                Filter
              </button>
            </div>

            <div className="filter-group">
              <label htmlFor="transfer-status-filter" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Status:</label>
              <select
                id="transfer-status-filter"
                className="select"
                value={statusFilter}
                onChange={(e) => {
                  setStatusFilter(e.target.value as TransferStatus | '');
                  setPage(0);
                }}
              >
                <option value="">All Statuses</option>
                <option value="PENDING">PENDING</option>
                <option value="PROCESSING">PROCESSING</option>
                <option value="SUCCEEDED">SUCCEEDED</option>
                <option value="FAILED">FAILED</option>
              </select>
            </div>

            <div className="filter-group">
              <label htmlFor="transfer-currency-filter" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>Currency:</label>
              <select
                id="transfer-currency-filter"
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

            <div className="filter-group">
              <label htmlFor="transfer-from-date" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>From:</label>
              <input
                id="transfer-from-date"
                type="date"
                className="input"
                value={fromDate}
                onChange={(e) => {
                  setFromDate(e.target.value);
                  setPage(0);
                }}
              />
            </div>

            <div className="filter-group">
              <label htmlFor="transfer-to-date" style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>To:</label>
              <input
                id="transfer-to-date"
                type="date"
                className="input"
                value={toDate}
                onChange={(e) => {
                  setToDate(e.target.value);
                  setPage(0);
                }}
              />
            </div>
          </div>

          {/* Transfers Table */}
          <div className="table-responsive">
            <table className="table" aria-label="Transfers List Table">
              <thead>
                <tr>
                  <th>Transfer ID</th>
                  <th>Status</th>
                  <th>Amount</th>
                  <th>Source Account</th>
                  <th>Destination Account</th>
                  <th>Attempts</th>
                  <th>Created (UTC)</th>
                  <th>Action</th>
                </tr>
              </thead>
              <tbody>
                {loading ? (
                  <TableSkeleton rows={size} columns={8} />
                ) : transfers.length === 0 ? (
                  <tr>
                    <td colSpan={8}>
                      <EmptyState
                        title="No transfers found"
                        message="No synthetic transfers match the selected filter criteria."
                      />
                    </td>
                  </tr>
                ) : (
                  transfers.map((tx) => (
                    <tr key={tx.id}>
                      <td className="mono-cell" title={tx.id}>
                        {truncateUuid(tx.id)}
                      </td>
                      <td>
                        <StatusBadge
                          status={tx.status}
                          pulse={tx.status === 'PENDING' || tx.status === 'PROCESSING'}
                        />
                      </td>
                      <td className="mono-cell" style={{ fontWeight: 600 }}>
                        {formatCurrency(tx.amount, tx.currency)}
                      </td>
                      <td className="mono-cell" title={tx.sourceAccountId}>
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                          <ArrowUpRight size={12} color="var(--status-danger)" />
                          {truncateUuid(tx.sourceAccountId)}
                        </span>
                      </td>
                      <td className="mono-cell" title={tx.destinationAccountId}>
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
                          <ArrowDownLeft size={12} color="var(--status-success)" />
                          {truncateUuid(tx.destinationAccountId)}
                        </span>
                      </td>
                      <td className="mono-cell">{tx.processingAttemptCount}</td>
                      <td className="mono-cell">{formatTimestamp(tx.createdAt)}</td>
                      <td>
                        <button
                          className="btn btn-secondary"
                          style={{ padding: '3px 8px', fontSize: '0.75rem' }}
                          onClick={() => handleSelectTransfer(tx)}
                          aria-label={`Inspect transfer ${tx.id}`}
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
      ) : (
        /* Detailed Transfer View */
        <div>
          <div style={{ marginBottom: '16px' }}>
            <button className="btn btn-secondary" onClick={handleBackToList}>
              <ArrowLeft size={16} />
              <span>Back to Transfer List</span>
            </button>
          </div>

          {detailError && <ErrorAlert error={detailError} onRetry={() => loadTransferDetails(selectedTransfer.id)} />}

          {/* Transfer Summary Card */}
          <div className="card" style={{ marginBottom: '24px' }}>
            <div className="card-header">
              <div>
                <span className="card-title">Transfer Lifecycle Details</span>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)', marginTop: 4 }}>
                  ID: {selectedTransfer.id}
                </div>
              </div>

              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                {selectedTransfer.status === 'PENDING' && (
                  <span className="badge badge-warning">
                    <Clock size={12} /> Accepted (Awaiting Worker Claim)
                  </span>
                )}
                {selectedTransfer.status === 'PROCESSING' && (
                  <span className="badge badge-warning">
                    <span className="pulse-dot" /> Leased & Processing
                  </span>
                )}
                {selectedTransfer.status === 'SUCCEEDED' && (
                  <span className="badge badge-success">
                    <CheckCircle2 size={12} /> Succeeded & Ledger Balanced
                  </span>
                )}
                {selectedTransfer.status === 'FAILED' && (
                  <span className="badge badge-danger">
                    <XCircle size={12} /> Terminally Declined
                  </span>
                )}
              </div>
            </div>

            {/* Lifecycle difference banner */}
            <div
              style={{
                background: selectedTransfer.status === 'PENDING'
                  ? 'var(--status-warning-bg)'
                  : selectedTransfer.status === 'SUCCEEDED'
                  ? 'var(--status-success-bg)'
                  : selectedTransfer.status === 'FAILED'
                  ? 'var(--status-danger-bg)'
                  : 'var(--bg-surface)',
                border: `1px solid ${
                  selectedTransfer.status === 'PENDING'
                    ? 'var(--status-warning-border)'
                    : selectedTransfer.status === 'SUCCEEDED'
                    ? 'var(--status-success-border)'
                    : selectedTransfer.status === 'FAILED'
                    ? 'var(--status-danger-border)'
                    : 'var(--border-color)'
                }`,
                padding: '12px 16px',
                borderRadius: 'var(--radius-md)',
                marginBottom: '20px',
                fontSize: '0.85rem',
              }}
            >
              {selectedTransfer.status === 'PENDING' && (
                <span>
                  <strong>Accepted for Processing:</strong> This transfer was committed into PostgreSQL and queued in the outbox. Funds have <em>not</em> moved yet until a worker processes the command. (Live polling active)
                </span>
              )}
              {selectedTransfer.status === 'PROCESSING' && (
                <span>
                  <strong>Worker In-Flight:</strong> An asynchronous consumer node currently holds a lease token to post this transfer. (Live polling active)
                </span>
              )}
              {selectedTransfer.status === 'SUCCEEDED' && (
                <span>
                  <strong>Completed:</strong> Exactly one debit and one credit ledger row have been atomically committed in PostgreSQL. Balances updated with zero floating-point arithmetic.
                </span>
              )}
              {selectedTransfer.status === 'FAILED' && (
                <span>
                  <strong>Terminally Failed:</strong> {selectedTransfer.failureMessage || selectedTransfer.failureCode || 'Transfer declined.'} Zero ledger rows committed; account balances unaffected.
                </span>
              )}
            </div>

            {/* Key Fields Grid */}
            <div className="detail-grid">
              <div className="detail-item">
                <span className="detail-label">Amount & Currency</span>
                <span className="detail-value font-mono" style={{ fontSize: '1.2rem', fontWeight: 700 }}>
                  {formatCurrency(selectedTransfer.amount, selectedTransfer.currency)}
                </span>
              </div>
              <div className="detail-item">
                <span className="detail-label">Source Account ID</span>
                <span className="detail-value font-mono">{selectedTransfer.sourceAccountId}</span>
              </div>
              <div className="detail-item">
                <span className="detail-label">Destination Account ID</span>
                <span className="detail-value font-mono">{selectedTransfer.destinationAccountId}</span>
              </div>
              <div className="detail-item">
                <span className="detail-label">Correlation ID</span>
                <span className="detail-value font-mono">{selectedTransfer.correlationId}</span>
              </div>
              <div className="detail-item">
                <span className="detail-label">Created At (UTC)</span>
                <span className="detail-value font-mono">{formatTimestamp(selectedTransfer.createdAt)}</span>
              </div>
              <div className="detail-item">
                <span className="detail-label">Completed At (UTC)</span>
                <span className="detail-value font-mono">{formatTimestamp(selectedTransfer.completedAt)}</span>
              </div>
              <div className="detail-item">
                <span className="detail-label">Total Attempts</span>
                <span className="detail-value font-mono">{selectedTransfer.processingAttemptCount}</span>
              </div>
              {selectedTransfer.failureCode && (
                <div className="detail-item">
                  <span className="detail-label">Failure Code</span>
                  <span className="detail-value font-mono" style={{ color: 'var(--status-danger)' }}>
                    {selectedTransfer.failureCode}
                  </span>
                </div>
              )}
            </div>
          </div>

          {/* Sub-sections: Timeline, Attempts, Ledger */}
          <div className="grid-2">
            {/* Status History Timeline */}
            <div className="card">
              <div className="card-header">
                <span className="card-title">
                  <History size={16} /> Status Transition Timeline
                </span>
                <span className="badge badge-neutral">{history.length} Transitions</span>
              </div>

              {history.length === 0 ? (
                <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No status history recorded yet.</div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  {history.map((entry, idx) => (
                    <div
                      key={entry.id || idx}
                      style={{
                        display: 'flex',
                        alignItems: 'flex-start',
                        gap: '12px',
                        paddingBottom: '12px',
                        borderBottom: idx < history.length - 1 ? '1px solid var(--border-color)' : 'none',
                      }}
                    >
                      <div
                        style={{
                          width: 24,
                          height: 24,
                          borderRadius: '50%',
                          background: 'var(--bg-surface)',
                          display: 'flex',
                          alignItems: 'center',
                          justifyContent: 'center',
                          fontSize: '0.75rem',
                          fontWeight: 700,
                          color: 'var(--text-muted)',
                        }}
                      >
                        {idx + 1}
                      </div>
                      <div style={{ flex: 1 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px' }}>
                          <span style={{ fontSize: '0.85rem', fontWeight: 600 }}>
                            {entry.fromStatus ? `${entry.fromStatus} → ` : 'Submitted → '}
                          </span>
                          <StatusBadge status={entry.toStatus} />
                        </div>
                        {entry.reasonCode && (
                          <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: 4, fontFamily: 'var(--font-mono)' }}>
                            Reason: {entry.reasonCode}
                          </div>
                        )}
                        <div style={{ fontSize: '0.725rem', color: 'var(--text-muted)', marginTop: 2, fontFamily: 'var(--font-mono)' }}>
                          {formatTimestamp(entry.occurredAt)}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>

            {/* Processing Attempts Audit */}
            <div className="card">
              <div className="card-header">
                <span className="card-title">
                  <Layers size={16} /> Execution Attempts Audit
                </span>
                <span className="badge badge-neutral">{attempts.length} Attempts</span>
              </div>

              {attempts.length === 0 ? (
                <div style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>No execution attempts audited yet.</div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                  {attempts.map((att) => (
                    <div
                      key={att.id}
                      style={{
                        background: 'var(--bg-surface)',
                        padding: '12px',
                        borderRadius: 'var(--radius-md)',
                        border: '1px solid var(--border-color)',
                      }}
                    >
                      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 6 }}>
                        <span style={{ fontWeight: 600, fontSize: '0.85rem' }}>Attempt #{att.attemptNumber}</span>
                        <span
                          className={`badge ${att.outcome === 'SUCCEEDED' ? 'badge-success' : att.outcome === 'IN_PROGRESS' ? 'badge-warning' : 'badge-danger'}`}
                        >
                          {att.outcome}
                        </span>
                      </div>
                      {att.safeErrorMessage && (
                        <div style={{ fontSize: '0.8rem', color: '#fecdd3', marginBottom: 4 }}>
                          {att.safeErrorMessage}
                        </div>
                      )}
                      <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: '0.725rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                        <span>Started: {formatTimestamp(att.startedAt)}</span>
                        {att.durationMs !== null && att.durationMs !== undefined && (
                          <span>Duration: {att.durationMs}ms</span>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* Balanced Double-Entry Ledger Legs */}
          <div className="table-wrapper" style={{ marginTop: '24px' }}>
            <div className="table-header-bar">
              <div style={{ fontWeight: 700, fontSize: '0.95rem', display: 'flex', alignItems: 'center', gap: 8 }}>
                <BookOpen size={16} />
                <span>Authoritative Double-Entry Ledger Postings</span>
              </div>
              <span className="badge badge-neutral">
                {ledgerEntries.length === 2 ? 'Balanced Pair (2 rows)' : `${ledgerEntries.length} rows`}
              </span>
            </div>

            <div className="table-responsive">
              <table className="table" aria-label="Transfer Ledger Postings">
                <thead>
                  <tr>
                    <th>Entry ID</th>
                    <th>Account ID</th>
                    <th>Entry Type</th>
                    <th>Amount</th>
                    <th>Posted At (UTC)</th>
                  </tr>
                </thead>
                <tbody>
                  {ledgerEntries.length === 0 ? (
                    <tr>
                      <td colSpan={5} style={{ textAlign: 'center', padding: '24px', color: 'var(--text-muted)' }}>
                        No ledger entries posted for this transfer (only posted upon SUCCEEDED outcome).
                      </td>
                    </tr>
                  ) : (
                    ledgerEntries.map((leg) => (
                      <tr key={leg.id}>
                        <td className="mono-cell" title={leg.id}>{truncateUuid(leg.id)}</td>
                        <td className="mono-cell" title={leg.accountId}>{truncateUuid(leg.accountId)}</td>
                        <td>
                          <span className={`badge ${leg.entryType === 'CREDIT' ? 'badge-success' : 'badge-warning'}`}>
                            {leg.entryType}
                          </span>
                        </td>
                        <td className="mono-cell" style={{ fontWeight: 700 }}>
                          {formatCurrency(leg.amount, leg.currency)}
                        </td>
                        <td className="mono-cell">{formatTimestamp(leg.postedAt)}</td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
