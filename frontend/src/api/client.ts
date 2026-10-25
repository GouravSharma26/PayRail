import {
  AccountResponse,
  ActuatorHealthResponse,
  ErrorEnvelope,
  LedgerEntryResponse,
  OutboxStatusResponse,
  PagedResponse,
  ProcessingAttemptResponse,
  ReconciliationRunDetailResponse,
  ReconciliationRunRecord,
  SystemHealthSummaryResponse,
  TransferResponse,
  TransferSearchFilter,
  TransferStatusHistoryResponse,
} from '../types/api';

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '');

function apiUrl(path: string): string {
  return `${API_BASE_URL}${path}`;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly correlationId?: string;
  readonly retryAfterSeconds?: number;

  constructor(status: number, code: string, message: string, correlationId?: string, retryAfterSeconds?: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.correlationId = correlationId;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const headers = new Headers(options.headers || {});
  if (!headers.has('Accept')) {
    headers.set('Accept', 'application/json');
  }

  const response = await fetch(apiUrl(path), {
    ...options,
    headers,
  });

  if (!response.ok) {
    let errorCode = 'HTTP_' + response.status;
    let errorMessage = `Request failed with status ${response.status}`;
    let correlationId: string | undefined;

    const retryAfterHeader = response.headers.get('Retry-After');
    const retryAfterSeconds = retryAfterHeader ? parseInt(retryAfterHeader, 10) : undefined;

    try {
      const contentType = response.headers.get('content-type');
      if (contentType && contentType.includes('application/json')) {
        const errorData = (await response.json()) as ErrorEnvelope;
        if (errorData.code) errorCode = errorData.code;
        if (errorData.message) errorMessage = errorData.message;
        if (errorData.correlationId) correlationId = errorData.correlationId;
      }
    } catch {
      // Non-JSON error payload; use default HTTP status message
    }

    throw new ApiError(response.status, errorCode, errorMessage, correlationId, retryAfterSeconds);
  }

  // Handle 204 or empty responses
  if (response.status === 204) {
    return {} as T;
  }

  return response.json() as Promise<T>;
}

export const api = {
  /* Ops and Health */
  getHealthSummary: (signal?: AbortSignal): Promise<SystemHealthSummaryResponse> =>
    request<SystemHealthSummaryResponse>('/api/v1/ops/health/summary', { signal }),

  getOutboxStatus: (signal?: AbortSignal): Promise<OutboxStatusResponse> =>
    request<OutboxStatusResponse>('/api/v1/ops/outbox/status', { signal }),

  getActuatorHealth: (signal?: AbortSignal): Promise<ActuatorHealthResponse> =>
    request<ActuatorHealthResponse>('/actuator/health', { signal }),

  getPrometheusMetrics: async (signal?: AbortSignal): Promise<string> => {
    const response = await fetch(apiUrl('/actuator/prometheus'), { signal });
    if (!response.ok) {
      throw new ApiError(response.status, 'METRICS_ERROR', `Failed to scrape metrics: status ${response.status}`);
    }
    return response.text();
  },

  /* Accounts */
  listAccounts: (
    page = 0,
    size = 20,
    status?: string,
    currency?: string,
    signal?: AbortSignal
  ): Promise<PagedResponse<AccountResponse>> => {
    const params = new URLSearchParams();
    params.set('page', String(page));
    params.set('size', String(size));
    if (status) params.set('status', status);
    if (currency) params.set('currency', currency);
    return request<PagedResponse<AccountResponse>>(`/api/v1/accounts?${params.toString()}`, { signal });
  },

  getAccount: (id: string, signal?: AbortSignal): Promise<AccountResponse> =>
    request<AccountResponse>(`/api/v1/accounts/${encodeURIComponent(id)}`, { signal }),

  getAccountTransfers: (
    id: string,
    page = 0,
    size = 20,
    signal?: AbortSignal
  ): Promise<PagedResponse<TransferResponse>> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    return request<PagedResponse<TransferResponse>>(`/api/v1/accounts/${encodeURIComponent(id)}/transfers?${params.toString()}`, { signal });
  },

  getAccountLedger: (
    id: string,
    page = 0,
    size = 20,
    signal?: AbortSignal
  ): Promise<PagedResponse<LedgerEntryResponse>> => {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    return request<PagedResponse<LedgerEntryResponse>>(`/api/v1/accounts/${encodeURIComponent(id)}/ledger?${params.toString()}`, { signal });
  },

  /* Transfers */
  searchTransfers: (filter: TransferSearchFilter = {}, signal?: AbortSignal): Promise<PagedResponse<TransferResponse>> => {
    const params = new URLSearchParams();
    if (filter.page !== undefined) params.set('page', String(filter.page));
    if (filter.size !== undefined) params.set('size', String(filter.size));
    if (filter.transferId) params.set('transferId', filter.transferId);
    if (filter.status) params.set('status', filter.status);
    if (filter.sourceAccountId) params.set('sourceAccountId', filter.sourceAccountId);
    if (filter.destinationAccountId) params.set('destinationAccountId', filter.destinationAccountId);
    if (filter.accountId) params.set('accountId', filter.accountId);
    if (filter.currency) params.set('currency', filter.currency);
    if (filter.fromDate) params.set('fromDate', filter.fromDate);
    if (filter.toDate) params.set('toDate', filter.toDate);

    return request<PagedResponse<TransferResponse>>(`/api/v1/transfers?${params.toString()}`, { signal });
  },

  getTransfer: (id: string, signal?: AbortSignal): Promise<TransferResponse> =>
    request<TransferResponse>(`/api/v1/transfers/${encodeURIComponent(id)}`, { signal }),

  getTransferHistory: (id: string, signal?: AbortSignal): Promise<TransferStatusHistoryResponse[]> =>
    request<TransferStatusHistoryResponse[]>(`/api/v1/transfers/${encodeURIComponent(id)}/history`, { signal }),

  getTransferAttempts: (id: string, signal?: AbortSignal): Promise<ProcessingAttemptResponse[]> =>
    request<ProcessingAttemptResponse[]>(`/api/v1/transfers/${encodeURIComponent(id)}/attempts`, { signal }),

  getTransferLedger: (id: string, signal?: AbortSignal): Promise<LedgerEntryResponse[]> =>
    request<LedgerEntryResponse[]>(`/api/v1/transfers/${encodeURIComponent(id)}/ledger`, { signal }),

  /* Reconciliation */
  listReconciliationRuns: (limit = 20, signal?: AbortSignal): Promise<ReconciliationRunRecord[]> =>
    request<ReconciliationRunRecord[]>(`/api/v1/ops/reconciliation/runs?limit=${limit}`, { signal }),

  getReconciliationRunDetail: (id: string, signal?: AbortSignal): Promise<ReconciliationRunDetailResponse> =>
    request<ReconciliationRunDetailResponse>(`/api/v1/ops/reconciliation/runs/${encodeURIComponent(id)}`, { signal }),

  triggerReconciliation: (): Promise<ReconciliationRunRecord> =>
    request<ReconciliationRunRecord>('/api/v1/ops/reconciliation/trigger', {
      method: 'POST',
    }),
};
