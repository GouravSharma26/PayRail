import React, { useEffect, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  Eye,
  FileCheck2,
  Play,
  RefreshCw,
  ShieldCheck,
  X,
  XCircle,
} from 'lucide-react';
import { api, ApiError } from '../api/client';
import { StatusBadge } from '../components/StatusBadge';
import { TableSkeleton } from '../components/Skeleton';
import { ErrorAlert } from '../components/ErrorAlert';
import { EmptyState } from '../components/EmptyState';
import { ReconciliationRunDetailResponse, ReconciliationRunRecord } from '../types/api';
import { formatTimestamp, truncateUuid } from '../utils/formatters';

interface ReconciliationPageProps {
  onInspectTransfer?: (transferId: string) => void;
}

export const ReconciliationPage: React.FC<ReconciliationPageProps> = ({ onInspectTransfer }) => {
  const [runs, setRuns] = useState<ReconciliationRunRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  // Trigger state
  const [triggering, setTriggering] = useState(false);
  const [triggerMessage, setTriggerMessage] = useState<{ type: 'success' | 'warning' | 'error'; text: string } | null>(null);

  // Selected run detail modal
  const [selectedRunDetail, setSelectedRunDetail] = useState<ReconciliationRunDetailResponse | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const loadRuns = async (signal?: AbortSignal) => {
    setLoading(true);
    setError(null);
    try {
      const data = await api.listReconciliationRuns(20, signal);
      setRuns(data);
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
    loadRuns(controller.signal);
    return () => controller.abort();
  }, []);

  const handleTriggerReconciliation = async () => {
    setTriggering(true);
    setTriggerMessage(null);
    try {
      const result = await api.triggerReconciliation();
      setTriggerMessage({
        type: 'success',
        text: `Audit executed successfully (${result.status}): ${result.summary}`,
      });
      loadRuns();
    } catch (err: unknown) {
      if (err instanceof ApiError) {
        if (err.status === 409) {
          setTriggerMessage({
            type: 'warning',
            text: 'Reconciliation is already running. Please wait for the current audit to complete.',
          });
        } else if (err.status === 429) {
          const retryAfter = err.retryAfterSeconds ? ` (retry after ${err.retryAfterSeconds}s)` : '';
          setTriggerMessage({
            type: 'warning',
            text: `Reconciliation cooldown active${retryAfter}. Please wait before triggering another audit.`,
          });
        } else {
          setTriggerMessage({
            type: 'error',
            text: `Audit trigger failed: ${err.message}`,
          });
        }
      } else {
        setTriggerMessage({
          type: 'error',
          text: err instanceof Error ? err.message : 'Failed to trigger reconciliation.',
        });
      }
    } finally {
      setTriggering(false);
    }
  };

  const handleInspectRun = async (runId: string) => {
    setDetailLoading(true);
    try {
      const detail = await api.getReconciliationRunDetail(runId);
      setSelectedRunDetail(detail);
    } catch (err) {
      console.error('Failed to load run detail:', err);
    } finally {
      setDetailLoading(false);
    }
  };

  return (
    <div>
      <div className="page-header">
        <div>
          <h2 className="page-title">Continuous Accounting Reconciliation</h2>
          <div className="page-description">
            Audit scans verifying double-entry balance equality, opening balance immutability, outbox health, and non-negative constraints.
          </div>
        </div>

        <div style={{ display: 'flex', gap: '10px', alignItems: 'center' }}>
          <button
            className="btn btn-primary"
            onClick={handleTriggerReconciliation}
            disabled={triggering || loading}
            aria-label="Trigger manual reconciliation audit"
          >
            <Play size={14} className={triggering ? 'animate-spin' : ''} />
            <span>{triggering ? 'Auditing...' : 'Trigger Audit'}</span>
          </button>

          <button
            className="btn btn-secondary"
            onClick={() => loadRuns()}
            disabled={loading}
            aria-label="Refresh reconciliation runs"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            <span>Refresh</span>
          </button>
        </div>
      </div>

      {triggerMessage && (
        <div
          className={`badge ${
            triggerMessage.type === 'success'
              ? 'badge-success'
              : triggerMessage.type === 'warning'
              ? 'badge-warning'
              : 'badge-danger'
          }`}
          style={{ width: '100%', padding: '12px 16px', fontSize: '0.85rem', marginBottom: '20px', borderRadius: 'var(--radius-md)' }}
        >
          {triggerMessage.type === 'success' && <CheckCircle2 size={16} />}
          {triggerMessage.type === 'warning' && <Clock size={16} />}
          {triggerMessage.type === 'error' && <XCircle size={16} />}
          <span>{triggerMessage.text}</span>
        </div>
      )}

      {error && <ErrorAlert error={error} onRetry={() => loadRuns()} />}

      {/* Explanatory Policy Card */}
      <div className="card" style={{ marginBottom: '24px' }}>
        <div className="card-header">
          <span className="card-title">
            <ShieldCheck size={16} color="var(--accent-cyan)" />
            <span>Authoritative Invariant Enforcement Policy</span>
          </span>
          <span className="badge badge-neutral">Append-Only Ledger</span>
        </div>
        <p style={{ fontSize: '0.85rem', color: 'var(--text-secondary)', lineHeight: 1.6 }}>
          Reconciliation in PayRail detects discrepancies across PostgreSQL double-entry legs and account balance projections.
          In accordance with financial accounting standards, <strong>reconciliation never performs silent automatic repair</strong> or modifies immutable ledger history. Discrepancies generate persistent audit issues for operator visibility.
        </p>
      </div>

      {/* Reconciliation Runs Table */}
      <div className="table-wrapper">
        <div className="table-header-bar">
          <div style={{ fontWeight: 700, fontSize: '0.95rem' }}>Audit Run History (Last 20 Runs)</div>
        </div>

        <div className="table-responsive">
          <table className="table" aria-label="Reconciliation Runs History">
            <thead>
              <tr>
                <th>Run ID</th>
                <th>Status</th>
                <th>Transfers Scanned</th>
                <th>Accounts Scanned</th>
                <th>Issues Detected</th>
                <th>Trigger Source</th>
                <th>Finished (UTC)</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <TableSkeleton rows={5} columns={8} />
              ) : runs.length === 0 ? (
                <tr>
                  <td colSpan={8}>
                    <EmptyState
                      icon={<FileCheck2 size={32} color="var(--text-muted)" />}
                      title="No reconciliation runs found"
                      message="Click 'Trigger Audit' above to execute a full accounting verification scan."
                    />
                  </td>
                </tr>
              ) : (
                runs.map((run) => (
                  <tr key={run.id}>
                    <td className="mono-cell" title={run.id}>{truncateUuid(run.id)}</td>
                    <td>
                      <StatusBadge status={run.status} />
                    </td>
                    <td className="mono-cell">{run.scannedTransfersCount}</td>
                    <td className="mono-cell">{run.scannedAccountsCount}</td>
                    <td className="mono-cell">
                      <span style={{ fontWeight: 700, color: run.issueCount > 0 ? 'var(--status-danger)' : 'var(--status-success)' }}>
                        {run.issueCount}
                      </span>
                    </td>
                    <td className="mono-cell">{run.triggerType}</td>
                    <td className="mono-cell">{formatTimestamp(run.finishedAt)}</td>
                    <td>
                      <button
                        className="btn btn-secondary"
                        style={{ padding: '3px 8px', fontSize: '0.75rem' }}
                        onClick={() => handleInspectRun(run.id)}
                        disabled={detailLoading}
                      >
                        <Eye size={12} />
                        <span>Inspect</span>
                      </button>
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Run Detail Modal */}
      {selectedRunDetail && (
        <div
          className="modal-overlay"
          role="dialog"
          aria-modal="true"
          aria-labelledby="run-detail-title"
          onClick={(e) => {
            if (e.target === e.currentTarget) setSelectedRunDetail(null);
          }}
        >
          <div className="modal" style={{ maxWidth: '850px' }}>
            <div className="modal-header">
              <div>
                <h3 id="run-detail-title" style={{ fontSize: '1.15rem', fontWeight: 700 }}>
                  Audit Run Execution Details
                </h3>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', fontFamily: 'var(--font-mono)' }}>
                  ID: {selectedRunDetail.run.id}
                </div>
              </div>
              <button className="btn-icon" onClick={() => setSelectedRunDetail(null)} aria-label="Close dialog">
                <X size={18} />
              </button>
            </div>

            <div className="modal-body">
              <div className="detail-grid">
                <div className="detail-item">
                  <span className="detail-label">Status</span>
                  <span className="detail-value">
                    <StatusBadge status={selectedRunDetail.run.status} />
                  </span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Trigger</span>
                  <span className="detail-value font-mono">{selectedRunDetail.run.triggerType}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Started At</span>
                  <span className="detail-value font-mono">{formatTimestamp(selectedRunDetail.run.startedAt)}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Finished At</span>
                  <span className="detail-value font-mono">{formatTimestamp(selectedRunDetail.run.finishedAt)}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Scanned Transfers</span>
                  <span className="detail-value font-mono">{selectedRunDetail.run.scannedTransfersCount}</span>
                </div>
                <div className="detail-item">
                  <span className="detail-label">Scanned Accounts</span>
                  <span className="detail-value font-mono">{selectedRunDetail.run.scannedAccountsCount}</span>
                </div>
              </div>

              <div style={{ marginBottom: '20px', padding: '12px 16px', background: 'var(--bg-surface)', borderRadius: 'var(--radius-md)' }}>
                <span className="detail-label">Summary: </span>
                <span style={{ fontSize: '0.85rem' }}>{selectedRunDetail.run.summary}</span>
              </div>

              {/* Detected Discrepancies Table */}
              <h4 style={{ fontSize: '0.95rem', fontWeight: 700, marginBottom: '12px', display: 'flex', alignItems: 'center', gap: 6 }}>
                <AlertTriangle size={16} color="var(--status-warning)" />
                <span>Detected Accounting Discrepancies ({selectedRunDetail.issues.length})</span>
              </h4>

              <div className="table-responsive">
                <table className="table" aria-label="Run Detected Issues">
                  <thead>
                    <tr>
                      <th>Issue Type</th>
                      <th>Severity</th>
                      <th>Transfer ID</th>
                      <th>Account ID</th>
                      <th>Details</th>
                      <th>Detected At (UTC)</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selectedRunDetail.issues.length === 0 ? (
                      <tr>
                        <td colSpan={6} style={{ textAlign: 'center', padding: '24px', color: 'var(--status-success)' }}>
                          Zero discrepancies detected. Ledger is fully balanced and consistent.
                        </td>
                      </tr>
                    ) : (
                      selectedRunDetail.issues.map((issue) => (
                        <tr key={issue.id}>
                          <td className="mono-cell" style={{ fontWeight: 600 }}>{issue.issueType}</td>
                          <td>
                            <StatusBadge status={issue.severity} />
                          </td>
                          <td className="mono-cell" title={issue.transferId || undefined}>
                            {issue.transferId && onInspectTransfer ? (
                              <button
                                onClick={() => {
                                  setSelectedRunDetail(null);
                                  onInspectTransfer(issue.transferId!);
                                }}
                                style={{ color: 'var(--accent-cyan)', textDecoration: 'underline' }}
                              >
                                {truncateUuid(issue.transferId)}
                              </button>
                            ) : (
                              truncateUuid(issue.transferId)
                            )}
                          </td>
                          <td className="mono-cell" title={issue.accountId || undefined}>
                            {truncateUuid(issue.accountId)}
                          </td>
                          <td style={{ fontSize: '0.75rem', maxWidth: '240px', wordBreak: 'break-all', fontFamily: 'var(--font-mono)' }}>
                            {issue.detailsJson}
                          </td>
                          <td className="mono-cell">{formatTimestamp(issue.detectedAt)}</td>
                        </tr>
                      ))
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
