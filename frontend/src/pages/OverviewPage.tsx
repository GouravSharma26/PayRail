import React, { useEffect, useState } from 'react';
import {
  Activity,
  AlertOctagon,
  ArrowDownLeft,
  ArrowUpRight,
  CheckCircle2,
  Clock,
  Database,
  FileCheck,
  RefreshCw,
  Server,
  TrendingUp,
} from 'lucide-react';
import { api } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import { Skeleton } from '../components/Skeleton';
import { ErrorAlert } from '../components/ErrorAlert';
import {
  OutboxStatusResponse,
  ReconciliationRunRecord,
  SystemHealthSummaryResponse,
  TransferResponse,
} from '../types/api';
import { formatCurrency, formatDurationSeconds, formatTimestamp, truncateUuid } from '../utils/formatters';

interface OverviewPageProps {
  onNavigateToTransfers: (transferId?: string) => void;
  onNavigateToReconciliation: () => void;
}

export const OverviewPage: React.FC<OverviewPageProps> = ({
  onNavigateToTransfers,
  onNavigateToReconciliation,
}) => {
  const [health, setHealth] = useState<SystemHealthSummaryResponse | null>(null);
  const [outbox, setOutbox] = useState<OutboxStatusResponse | null>(null);
  const [latestRun, setLatestRun] = useState<ReconciliationRunRecord | null>(null);
  const [recentTransfers, setRecentTransfers] = useState<TransferResponse[]>([]);

  // Transfer count breakdowns
  const [counts, setCounts] = useState<{
    total: number;
    succeeded: number;
    failed: number;
    pending: number;
    processing: number;
  }>({ total: 0, succeeded: 0, failed: 0, pending: 0, processing: 0 });

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  const loadData = async (signal?: AbortSignal) => {
    setLoading(true);
    setError(null);
    try {
      const [
        healthRes,
        outboxRes,
        runsRes,
        recentRes,
        succeededRes,
        failedRes,
        pendingRes,
        processingRes,
      ] = await Promise.all([
        api.getHealthSummary(signal),
        api.getOutboxStatus(signal),
        api.listReconciliationRuns(1, signal),
        api.searchTransfers({ page: 0, size: 5 }, signal),
        api.searchTransfers({ status: 'SUCCEEDED', page: 0, size: 1 }, signal),
        api.searchTransfers({ status: 'FAILED', page: 0, size: 1 }, signal),
        api.searchTransfers({ status: 'PENDING', page: 0, size: 1 }, signal),
        api.searchTransfers({ status: 'PROCESSING', page: 0, size: 1 }, signal),
      ]);

      setHealth(healthRes);
      setOutbox(outboxRes);
      setLatestRun(runsRes.length > 0 ? runsRes[0] : null);
      setRecentTransfers(recentRes.items);
      setCounts({
        total: recentRes.totalItems,
        succeeded: succeededRes.totalItems,
        failed: failedRes.totalItems,
        pending: pendingRes.totalItems,
        processing: processingRes.totalItems,
      });
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
    loadData(controller.signal);

    // Bounded refresh (every 10s)
    const interval = setInterval(() => {
      loadData(controller.signal);
    }, 10000);

    return () => {
      controller.abort();
      clearInterval(interval);
    };
  }, []);

  return (
    <div>
      <div className="page-header">
        <div>
          <h2 className="page-title">Operational Overview</h2>
          <div className="page-description">
            Real-time telemetry from authoritative PostgreSQL storage, Kafka outbox pipeline, and accounting audits.
          </div>
        </div>
        <button
          className="btn btn-secondary"
          onClick={() => loadData()}
          disabled={loading}
          aria-label="Refresh telemetry"
        >
          <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
          <span>Refresh</span>
        </button>
      </div>

      {error && <ErrorAlert error={error} onRetry={() => loadData()} />}

      {/* System Health Summary Bar */}
      <div className="grid-3">
        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <Database size={16} /> PostgreSQL Invariants
            </span>
            <StatusBadge status={health?.dbStatus || 'UNKNOWN'} pulse={health?.dbStatus === 'UP'} />
          </div>
          <div className="card-value" style={{ fontSize: '1.25rem' }}>
            {health?.dbStatus === 'UP' ? 'Connected & Healthy' : 'Unavailable'}
          </div>
          <div className="card-subtext">
            Authoritative source for accounts, transfers & double-entry ledger rows
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <Server size={16} /> Kafka Command Broker
            </span>
            <StatusBadge status={health?.kafkaStatus || 'UNKNOWN'} pulse={health?.kafkaStatus === 'UP'} />
          </div>
          <div className="card-value" style={{ fontSize: '1.25rem' }}>
            {health?.kafkaStatus === 'UP' ? 'Connected (KRaft)' : 'Unavailable'}
          </div>
          <div className="card-subtext">
            Topic: payrail.transfer-commands.v1 | Consumer group active
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <FileCheck size={16} /> Continuous Reconciliation
            </span>
            <StatusBadge
              status={latestRun?.status || health?.lastReconciliationStatus || 'UNKNOWN'}
            />
          </div>
          <div className="card-value" style={{ fontSize: '1.25rem' }}>
            {latestRun
              ? `${latestRun.issueCount} Issues Detected`
              : health?.openReconciliationIssues !== null && health?.openReconciliationIssues !== undefined
              ? `${health.openReconciliationIssues} Open Issues`
              : 'Audit Pending'}
          </div>
          <div className="card-subtext">
            Last run: {formatTimestamp(latestRun?.finishedAt || health?.lastReconciliationTime)}
          </div>
        </div>
      </div>

      {/* Metric Counters Grid */}
      <h3 style={{ fontSize: '1.1rem', fontWeight: 700, margin: '24px 0 16px', color: 'var(--text-primary)' }}>
        Simulated Transfer Pipeline Metrics
      </h3>

      <div className="grid-4">
        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <TrendingUp size={16} /> Total Transfers
            </span>
            <span className="badge badge-neutral">Simulated</span>
          </div>
          <div className="card-value">{loading ? <Skeleton height="2rem" /> : counts.total}</div>
          <div className="card-subtext">Accepted via idempotent POST /transfers</div>
        </div>

        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <CheckCircle2 size={16} /> Succeeded
            </span>
            <span className="badge badge-success">Balanced</span>
          </div>
          <div className="card-value" style={{ color: 'var(--status-success)' }}>
            {loading ? <Skeleton height="2rem" /> : counts.succeeded}
          </div>
          <div className="card-subtext">Committed with dual debit/credit ledger rows</div>
        </div>

        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <Clock size={16} /> In-Flight / Pending
            </span>
            <span className="badge badge-warning">Async</span>
          </div>
          <div className="card-value" style={{ color: 'var(--status-warning)' }}>
            {loading ? <Skeleton height="2rem" /> : counts.pending + counts.processing}
          </div>
          <div className="card-subtext">
            {counts.pending} PENDING &bull; {counts.processing} PROCESSING
          </div>
        </div>

        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <AlertOctagon size={16} /> Terminal Declines
            </span>
            <span className="badge badge-danger">Failed</span>
          </div>
          <div className="card-value" style={{ color: 'var(--status-danger)' }}>
            {loading ? <Skeleton height="2rem" /> : counts.failed}
          </div>
          <div className="card-subtext">Business declines & exhausted technical retries</div>
        </div>
      </div>

      {/* Outbox & Broker Queue Telemetry */}
      <div className="grid-2">
        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <Activity size={16} /> Transactional Outbox Pipeline
            </span>
            <span className="badge badge-info">
              {outbox?.publisherEnabled ? 'Publisher Active' : 'Publisher Paused'}
            </span>
          </div>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '16px', marginTop: '12px' }}>
            <div>
              <div className="detail-label">Unpublished Backlog</div>
              <div className="card-value" style={{ fontSize: '1.4rem' }}>
                {loading ? <Skeleton height="1.6rem" /> : outbox?.totalUnpublished ?? '—'}
              </div>
            </div>
            <div>
              <div className="detail-label">Oldest Event Age</div>
              <div className="card-value" style={{ fontSize: '1.4rem' }}>
                {loading ? (
                  <Skeleton height="1.6rem" />
                ) : (
                  formatDurationSeconds(outbox?.oldestUnpublishedAgeSeconds)
                )}
              </div>
            </div>
            <div>
              <div className="detail-label">Retrying After Failure</div>
              <div className="detail-value font-mono">
                {loading ? <Skeleton height="1.2rem" /> : `${outbox?.failedCount ?? 0} events`}
              </div>
            </div>
            <div>
              <div className="detail-label">Retry Exhausted (DLQ)</div>
              <div className="detail-value font-mono" style={{ color: (outbox?.exhaustedCount ?? 0) > 0 ? 'var(--status-danger)' : undefined }}>
                {loading ? <Skeleton height="1.2rem" /> : `${outbox?.exhaustedCount ?? 0} events`}
              </div>
            </div>
          </div>
        </div>

        {/* Audit Status */}
        <div className="card">
          <div className="card-header">
            <span className="card-title">
              <FileCheck size={16} /> Latest Audit Report
            </span>
            <button
              className="btn btn-secondary"
              style={{ fontSize: '0.75rem', padding: '4px 8px' }}
              onClick={onNavigateToReconciliation}
            >
              View Runs
            </button>
          </div>
          {latestRun ? (
            <div style={{ marginTop: '8px' }}>
              <div style={{ marginBottom: '10px' }}>
                <span className="detail-label">Summary: </span>
                <span style={{ fontSize: '0.875rem', color: 'var(--text-secondary)' }}>{latestRun.summary}</span>
              </div>
              <div className="detail-grid">
                <div className="detail-item">
                  <span className="detail-label">Scanned Transfers</span>
                  <span className="detail-value font-mono">{latestRun.scannedTransfersCount}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Scanned Accounts</span>
                  <span className="detail-value font-mono">{latestRun.scannedAccountsCount}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Discrepancies</span>
                  <span className="detail-value font-mono" style={{ color: latestRun.issueCount > 0 ? 'var(--status-danger)' : 'var(--status-success)' }}>
                    {latestRun.issueCount}
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Trigger</span>
                  <span className="detail-value font-mono">{latestRun.triggerType}</span>
                </div>
              </div>
            </div>
          ) : (
            <div style={{ padding: '24px 0', color: 'var(--text-muted)', fontSize: '0.85rem' }}>
              No reconciliation runs executed yet. Use the Reconciliation tab to trigger an audit.
            </div>
          )}
        </div>
      </div>

      {/* Recent Transfers Mini-Table */}
      <div className="table-wrapper">
        <div className="table-header-bar">
          <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>Recent Simulated Transfers</div>
          <button
            className="btn btn-secondary"
            onClick={() => onNavigateToTransfers()}
            style={{ fontSize: '0.8rem' }}
          >
            <span>View All Transfers</span>
            <ArrowUpRight size={14} />
          </button>
        </div>

        <div className="table-responsive">
          <table className="table" aria-label="Recent transfers">
            <thead>
              <tr>
                <th>Transfer ID</th>
                <th>Status</th>
                <th>Amount</th>
                <th>Source Account</th>
                <th>Destination Account</th>
                <th>Created (UTC)</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                Array.from({ length: 5 }).map((_, idx) => (
                  <tr key={idx}>
                    <td colSpan={7}>
                      <Skeleton height="1.5rem" />
                    </td>
                  </tr>
                ))
              ) : recentTransfers.length === 0 ? (
                <tr>
                  <td colSpan={7} style={{ textAlign: 'center', padding: '32px', color: 'var(--text-muted)' }}>
                    No simulated transfers submitted yet.
                  </td>
                </tr>
              ) : (
                recentTransfers.map((tx) => (
                  <tr key={tx.id}>
                    <td className="mono-cell" title={tx.id}>
                      {truncateUuid(tx.id)}
                    </td>
                    <td>
                      <StatusBadge status={tx.status} pulse={tx.status === 'PENDING' || tx.status === 'PROCESSING'} />
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
                    <td className="mono-cell">{formatTimestamp(tx.createdAt)}</td>
                    <td>
                      <button
                        className="btn btn-secondary"
                        style={{ padding: '3px 8px', fontSize: '0.75rem' }}
                        onClick={() => onNavigateToTransfers(tx.id)}
                      >
                        Inspect
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};
