import React, { useEffect, useState, useCallback, useRef } from 'react';
import {
  Activity,
  AlertTriangle,
  RefreshCw,
  CheckCircle,
  Clock,
  Cpu,
  Database,
  Layers,
  Radio,
  Server,
  ShieldAlert,
  XCircle,
} from 'lucide-react';
import { api, ApiError } from '../api/client';
import {
  ActuatorHealthResponse,
  OutboxStatusResponse,
  SystemHealthSummaryResponse,
} from '../types/api';
import { CardSkeleton } from '../components/Skeleton';
import { ErrorAlert } from '../components/ErrorAlert';
import { formatDurationSeconds, formatTimestamp } from '../utils/formatters';

interface PrometheusParsedMetrics {
  transfersSubmitted: number;
  transfersSucceeded: number;
  transfersFailed: number;
  consumerReceived: number;
  consumerRetried: number;
  consumerDlqRouted: number;
  outboxPublished: number;
  outboxFailed: number;
  outboxExhausted: number;
  reconciliationRuns: number;
  reconciliationIssues: number;
  rawMetricCount: number;
}

export const OperationsPage: React.FC = () => {
  const [healthSummary, setHealthSummary] = useState<SystemHealthSummaryResponse | null>(null);
  const [outboxStatus, setOutboxStatus] = useState<OutboxStatusResponse | null>(null);
  const [actuatorHealth, setActuatorHealth] = useState<ActuatorHealthResponse | null>(null);
  const [metrics, setMetrics] = useState<PrometheusParsedMetrics | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<ApiError | null>(null);
  const [lastRefreshed, setLastRefreshed] = useState<Date | null>(null);
  const [autoRefresh, setAutoRefresh] = useState(false);

  const autoRefreshTimerRef = useRef<number | null>(null);
  const abortControllerRef = useRef<AbortController | null>(null);

  const parsePrometheusText = (text: string): PrometheusParsedMetrics => {
    const lines = text.split('\n');
    let transfersSubmitted = 0;
    let transfersSucceeded = 0;
    let transfersFailed = 0;
    let consumerReceived = 0;
    let consumerRetried = 0;
    let consumerDlqRouted = 0;
    let outboxPublished = 0;
    let outboxFailed = 0;
    let outboxExhausted = 0;
    let reconciliationRuns = 0;
    let reconciliationIssues = 0;
    let rawMetricCount = 0;

    for (const line of lines) {
      const trimmed = line.trim();
      if (!trimmed || trimmed.startsWith('#')) continue;
      rawMetricCount++;

      const match = trimmed.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)\s+([0-9.eE+-]+)/);
      if (match) {
        const name = match[1];
        const val = parseFloat(match[2]) || 0;

        if (name.includes('payrail_transfers_submitted_total')) transfersSubmitted += val;
        else if (name.includes('payrail_transfers_succeeded_total')) transfersSucceeded += val;
        else if (name.includes('payrail_transfers_failed_total')) transfersFailed += val;
        else if (name.includes('payrail_consumer_events_received_total')) consumerReceived += val;
        else if (name.includes('payrail_consumer_events_retried_total')) consumerRetried += val;
        else if (name.includes('payrail_consumer_dlq_routed_total')) consumerDlqRouted += val;
        else if (name.includes('payrail_outbox_events_published_total')) outboxPublished += val;
        else if (name.includes('payrail_outbox_events_failed_total')) outboxFailed += val;
        else if (name.includes('payrail_outbox_events_exhausted_total')) outboxExhausted += val;
        else if (name.includes('payrail_reconciliation_runs_total')) reconciliationRuns += val;
        else if (name.includes('payrail_reconciliation_issues_detected_total')) reconciliationIssues += val;
      }
    }

    return {
      transfersSubmitted,
      transfersSucceeded,
      transfersFailed,
      consumerReceived,
      consumerRetried,
      consumerDlqRouted,
      outboxPublished,
      outboxFailed,
      outboxExhausted,
      reconciliationRuns,
      reconciliationIssues,
      rawMetricCount,
    };
  };

  const fetchOperationsData = useCallback(async () => {
    if (abortControllerRef.current) {
      abortControllerRef.current.abort();
    }
    const controller = new AbortController();
    abortControllerRef.current = controller;

    setLoading(true);
    setError(null);

    try {
      const [healthRes, outboxRes, actuatorRes, metricsRes] = await Promise.allSettled([
        api.getHealthSummary(controller.signal),
        api.getOutboxStatus(controller.signal),
        api.getActuatorHealth(controller.signal),
        api.getPrometheusMetrics(controller.signal),
      ]);

      if (healthRes.status === 'fulfilled') {
        setHealthSummary(healthRes.value);
      } else if (healthRes.reason instanceof ApiError) {
        setError(healthRes.reason);
      }

      if (outboxRes.status === 'fulfilled') {
        setOutboxStatus(outboxRes.value);
      }

      if (actuatorRes.status === 'fulfilled') {
        setActuatorHealth(actuatorRes.value);
      }

      if (metricsRes.status === 'fulfilled') {
        const parsed = parsePrometheusText(metricsRes.value);
        setMetrics(parsed);
      }

      setLastRefreshed(new Date());
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') return;
      if (err instanceof ApiError) setError(err);
      else setError(new ApiError(500, 'CLIENT_ERROR', 'Failed to fetch operations diagnostics.'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchOperationsData();

    return () => {
      if (abortControllerRef.current) {
        abortControllerRef.current.abort();
      }
      if (autoRefreshTimerRef.current) {
        window.clearInterval(autoRefreshTimerRef.current);
      }
    };
  }, [fetchOperationsData]);

  useEffect(() => {
    if (autoRefresh) {
      autoRefreshTimerRef.current = window.setInterval(() => {
        fetchOperationsData();
      }, 10000);
    } else {
      if (autoRefreshTimerRef.current) {
        window.clearInterval(autoRefreshTimerRef.current);
        autoRefreshTimerRef.current = null;
      }
    }

    return () => {
      if (autoRefreshTimerRef.current) {
        window.clearInterval(autoRefreshTimerRef.current);
      }
    };
  }, [autoRefresh, fetchOperationsData]);

  const renderStatusBadge = (status?: string | null) => {
    const s = (status || 'UNKNOWN').toUpperCase();
    if (s === 'UP' || s === 'HEALTHY' || s === 'CLEAN') {
      return (
        <span className="badge badge-success" style={{ gap: '0.35rem' }}>
          <CheckCircle size={13} />
          {s}
        </span>
      );
    }
    if (s === 'DEGRADED' || s === 'WARNING' || s === 'DISCREPANCIES_DETECTED') {
      return (
        <span className="badge badge-warning" style={{ gap: '0.35rem' }}>
          <AlertTriangle size={13} />
          {s}
        </span>
      );
    }
    return (
      <span className="badge badge-danger" style={{ gap: '0.35rem' }}>
        <XCircle size={13} />
        {s}
      </span>
    );
  };

  return (
    <div className="page-container">
      {/* Header with Title and Diagnostics Control */}
      <div className="page-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', flexWrap: 'wrap', gap: '1rem' }}>
        <div>
          <h1 className="page-title" style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
            <Activity className="text-primary" size={26} />
            Operations & Health Diagnostics
          </h1>
          <p className="page-subtitle">
            Telemetry, outbox backlog, event delivery health, and reconciliation diagnostics
          </p>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', flexWrap: 'wrap' }}>
          {lastRefreshed && (
            <span style={{ fontSize: '0.8rem', color: 'var(--text-muted)' }}>
              Last updated: {lastRefreshed.toLocaleTimeString()}
            </span>
          )}

          <label style={{ display: 'flex', alignItems: 'center', gap: '0.4rem', fontSize: '0.85rem', cursor: 'pointer', color: 'var(--text-secondary)' }}>
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
              style={{ cursor: 'pointer' }}
            />
            Auto-refresh (10s)
          </label>

          <button
            type="button"
            className="btn btn-secondary"
            onClick={fetchOperationsData}
            disabled={loading}
            style={{ display: 'flex', alignItems: 'center', gap: '0.4rem' }}
          >
            <RefreshCw size={15} className={loading ? 'spin' : ''} />
            Refresh
          </button>
        </div>
      </div>

      {error && <ErrorAlert error={error} onRetry={fetchOperationsData} />}

      {/* Read-Only Safety Banner */}
      <div
        className="card"
        style={{
          backgroundColor: 'rgba(56, 189, 248, 0.05)',
          borderColor: 'rgba(56, 189, 248, 0.25)',
          marginBottom: '1.5rem',
          display: 'flex',
          gap: '1rem',
          alignItems: 'flex-start',
        }}
      >
        <ShieldAlert size={22} style={{ color: 'var(--color-primary)', flexShrink: 0, marginTop: '2px' }} />
        <div>
          <div style={{ fontWeight: 600, color: 'var(--color-primary)', marginBottom: '0.25rem' }}>
            Read-Only Operational Diagnostics Notice
          </div>
          <p style={{ margin: 0, fontSize: '0.875rem', color: 'var(--text-secondary)', lineHeight: 1.5 }}>
            To preserve double-entry balance integrity and prevent out-of-order state corruption, manual event replay,
            DLQ reprocessing, and ledger manipulation are intentionally prohibited by PayRail invariants.
            Operational interventions are executed strictly through audited services and reconciliation routines.
          </p>
        </div>
      </div>

      {loading && !healthSummary ? (
        <CardSkeleton count={3} />
      ) : (
        <>
          {/* Top Status Grid */}
          <div className="grid grid-cols-4" style={{ marginBottom: '1.5rem' }}>
            <div className="card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>Overall System</span>
                {renderStatusBadge(healthSummary?.status)}
              </div>
              <div style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                {healthSummary?.status || 'UNKNOWN'}
              </div>
              <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.4rem' }}>
                Reported at: {healthSummary ? formatTimestamp(healthSummary.timestamp) : '—'}
              </div>
            </div>

            <div className="card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>PostgreSQL Database</span>
                {renderStatusBadge(healthSummary?.dbStatus)}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '1.5rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                <Database size={20} className="text-primary" />
                {healthSummary?.dbStatus || 'UNKNOWN'}
              </div>
              <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.4rem' }}>
                Authoritative persistence layer
              </div>
            </div>

            <div className="card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>Kafka Message Broker</span>
                {renderStatusBadge(healthSummary?.kafkaStatus)}
              </div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '1.5rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                <Radio size={20} className="text-primary" />
                {healthSummary?.kafkaStatus || 'UNKNOWN'}
              </div>
              <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.4rem' }}>
                Asynchronous event broker
              </div>
            </div>

            <div className="card">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
                <span style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>Outbox Backlog</span>
                <span className={`badge ${outboxStatus?.totalUnpublished ? 'badge-warning' : 'badge-neutral'}`}>
                  {outboxStatus?.totalUnpublished || 0}
                </span>
              </div>
              <div style={{ fontSize: '1.5rem', fontWeight: 700, color: outboxStatus?.totalUnpublished ? 'var(--color-warning)' : 'var(--text-primary)' }}>
                {outboxStatus?.totalUnpublished ?? '—'}
              </div>
              <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.4rem' }}>
                {outboxStatus?.oldestUnpublishedAgeSeconds != null
                  ? `Oldest event: ${formatDurationSeconds(outboxStatus.oldestUnpublishedAgeSeconds)}`
                  : 'No pending outbox events'}
              </div>
            </div>
          </div>

          {/* Outbox Backlog & Publisher Telemetry */}
          <div className="card" style={{ marginBottom: '1.5rem' }}>
            <div className="card-header" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                <Layers className="text-primary" size={20} />
                <h2 style={{ fontSize: '1.15rem', fontWeight: 600, margin: 0 }}>
                  Transactional Outbox Telemetry
                </h2>
              </div>
              <span className={`badge ${outboxStatus?.publisherEnabled ? 'badge-success' : 'badge-danger'}`}>
                {outboxStatus?.publisherEnabled ? 'Publisher Active' : 'Publisher Disabled'}
              </span>
            </div>

            <div className="grid grid-cols-4" style={{ gap: '1rem', marginTop: '1rem' }}>
              <div style={{ padding: '1rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>
                  Pending Events
                </div>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {outboxStatus?.pendingCount ?? 0}
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.2rem' }}>
                  Awaiting Kafka publish
                </div>
              </div>

              <div style={{ padding: '1rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>
                  Failed Attempts
                </div>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: outboxStatus?.failedCount ? 'var(--color-warning)' : 'var(--text-primary)' }}>
                  {outboxStatus?.failedCount ?? 0}
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.2rem' }}>
                  Eligible for retry
                </div>
              </div>

              <div style={{ padding: '1rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>
                  Exhausted Events
                </div>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: outboxStatus?.exhaustedCount ? 'var(--color-danger)' : 'var(--text-primary)' }}>
                  {outboxStatus?.exhaustedCount ?? 0}
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.2rem' }}>
                  Max attempts exceeded
                </div>
              </div>

              <div style={{ padding: '1rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                <div style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginBottom: '0.25rem' }}>
                  Oldest Event Age
                </div>
                <div style={{ fontSize: '1.4rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                  {formatDurationSeconds(outboxStatus?.oldestUnpublishedAgeSeconds)}
                </div>
                <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)', marginTop: '0.2rem' }}>
                  Queue latency indicator
                </div>
              </div>
            </div>
          </div>

          {/* Consumer, DLQ & Metric Diagnostics */}
          <div className="grid grid-cols-2" style={{ gap: '1.5rem', marginBottom: '1.5rem' }}>
            {/* Kafka Consumer & DLQ Summary */}
            <div className="card">
              <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                <Server className="text-primary" size={18} />
                <h3 style={{ fontSize: '1.05rem', fontWeight: 600, margin: 0 }}>
                  Event Consumer & Dead-Letter Queue (DLQ)
                </h3>
              </div>

              <div style={{ marginTop: '1rem', display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', padding: '0.75rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Events Received</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Total messages ingested from topic</div>
                  </div>
                  <div style={{ fontSize: '1.2rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                    {metrics ? metrics.consumerReceived : '—'}
                  </div>
                </div>

                <div style={{ display: 'flex', justifyContent: 'space-between', padding: '0.75rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Consumer Retries</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Transient retry attempts</div>
                  </div>
                  <div style={{ fontSize: '1.2rem', fontWeight: 700, color: 'var(--color-warning)' }}>
                    {metrics ? metrics.consumerRetried : '—'}
                  </div>
                </div>

                <div style={{ display: 'flex', justifyContent: 'space-between', padding: '0.75rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>DLQ Routed Events</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Exhausted consumer messages diverted to DLQ</div>
                  </div>
                  <div style={{ fontSize: '1.2rem', fontWeight: 700, color: metrics && metrics.consumerDlqRouted > 0 ? 'var(--color-danger)' : 'var(--text-primary)' }}>
                    {metrics ? metrics.consumerDlqRouted : '—'}
                  </div>
                </div>
              </div>
            </div>

            {/* Reconciliation Diagnostics */}
            <div className="card">
              <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                <Clock className="text-primary" size={18} />
                <h3 style={{ fontSize: '1.05rem', fontWeight: 600, margin: 0 }}>
                  Ledger Reconciliation Health
                </h3>
              </div>

              <div style={{ marginTop: '1rem', display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', padding: '0.75rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Last Audit Status</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>
                      Completed at: {healthSummary?.lastReconciliationTime ? formatTimestamp(healthSummary.lastReconciliationTime) : 'Never'}
                    </div>
                  </div>
                  <div>
                    {renderStatusBadge(healthSummary?.lastReconciliationStatus)}
                  </div>
                </div>

                <div style={{ display: 'flex', justifyContent: 'space-between', padding: '0.75rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Open Discrepancies</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Unresolved balance or outbox anomalies</div>
                  </div>
                  <div style={{ fontSize: '1.2rem', fontWeight: 700, color: healthSummary?.openReconciliationIssues ? 'var(--color-danger)' : 'var(--text-primary)' }}>
                    {healthSummary?.openReconciliationIssues ?? 0}
                  </div>
                </div>

                <div style={{ display: 'flex', justifyContent: 'space-between', padding: '0.75rem', backgroundColor: 'var(--bg-secondary)', borderRadius: '6px' }}>
                  <div>
                    <div style={{ fontWeight: 600, color: 'var(--text-primary)' }}>Total Audit Executions</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Historical audit runs recorded</div>
                  </div>
                  <div style={{ fontSize: '1.2rem', fontWeight: 700, color: 'var(--text-primary)' }}>
                    {metrics ? metrics.reconciliationRuns : '—'}
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Actuator & Runtime Components */}
          {actuatorHealth?.components && (
            <div className="card">
              <div className="card-header" style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                <Cpu className="text-primary" size={18} />
                <h3 style={{ fontSize: '1.05rem', fontWeight: 600, margin: 0 }}>
                  Spring Boot Actuator Health Probes
                </h3>
              </div>

              <div className="table-responsive" style={{ marginTop: '1rem' }}>
                <table className="table">
                  <thead>
                    <tr>
                      <th>Component</th>
                      <th>Status</th>
                      <th>Details / Diagnostics</th>
                    </tr>
                  </thead>
                  <tbody>
                    {Object.entries(actuatorHealth.components).map(([name, comp]) => (
                      <tr key={name}>
                        <td style={{ fontWeight: 600, color: 'var(--text-primary)' }}>{name}</td>
                        <td>{renderStatusBadge(comp.status)}</td>
                        <td style={{ fontSize: '0.85rem', color: 'var(--text-secondary)' }}>
                          {comp.details ? JSON.stringify(comp.details) : 'Nominal operational status'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
};
