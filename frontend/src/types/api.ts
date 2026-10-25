export type AccountStatus = 'ACTIVE' | 'INACTIVE';

export interface AccountResponse {
  id: string;
  displayName: string;
  currency: string;
  openingBalance: string;
  currentBalance: string;
  status: AccountStatus;
  createdAt: string;
  updatedAt: string;
}

export type TransferStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED';

export interface TransferResponse {
  id: string;
  sourceAccountId: string;
  destinationAccountId: string;
  amount: string;
  currency: string;
  status: TransferStatus;
  failureCode?: string | null;
  failureMessage?: string | null;
  createdAt: string;
  updatedAt: string;
  completedAt?: string | null;
  processingAttemptCount: number;
  correlationId: string;
}

export interface TransferStatusHistoryResponse {
  id: number;
  transferId: string;
  fromStatus: TransferStatus | null;
  toStatus: TransferStatus;
  reasonCode?: string | null;
  attemptNumber?: number | null;
  correlationId?: string | null;
  occurredAt: string;
}

export interface ProcessingAttemptResponse {
  id: string;
  transferId: string;
  attemptNumber: number;
  outcome: string;
  errorCode?: string | null;
  safeErrorMessage?: string | null;
  startedAt: string;
  finishedAt: string;
  durationMs?: number | null;
}

export interface LedgerEntryResponse {
  id: string;
  transferId: string;
  accountId: string;
  entryType: 'DEBIT' | 'CREDIT';
  amount: string;
  currency: string;
  postedAt: string;
}

export interface PagedResponse<T> {
  items: T[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface TransferSearchFilter {
  transferId?: string;
  status?: TransferStatus;
  sourceAccountId?: string;
  destinationAccountId?: string;
  accountId?: string;
  currency?: string;
  fromDate?: string;
  toDate?: string;
  page?: number;
  size?: number;
}

export interface ReconciliationRunRecord {
  id: string;
  startedAt: string;
  finishedAt: string;
  status: 'CLEAN' | 'DISCREPANCIES_DETECTED' | string;
  scannedTransfersCount: number;
  scannedAccountsCount: number;
  issueCount: number;
  triggerType: string;
  summary: string;
}

export interface ReconciliationIssueRecord {
  id: string;
  runId: string;
  issueType: string;
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL' | string;
  accountId?: string | null;
  transferId?: string | null;
  outboxEventId?: string | null;
  detectedAt: string;
  detailsJson: string;
  status: string;
  resolutionNote?: string | null;
}

export interface ReconciliationRunDetailResponse {
  run: ReconciliationRunRecord;
  issues: ReconciliationIssueRecord[];
}

export interface OutboxStatusResponse {
  pendingCount: number;
  failedCount: number;
  exhaustedCount: number;
  totalUnpublished: number;
  oldestUnpublishedAgeSeconds: number | null;
  publisherEnabled: boolean;
}

export interface SystemHealthSummaryResponse {
  status: string;
  dbStatus: string;
  kafkaStatus: string;
  outboxBacklog: number | null;
  lastReconciliationStatus: string | null;
  lastReconciliationTime: string | null;
  openReconciliationIssues: number | null;
  timestamp: string;
}

export interface ErrorDetail {
  field?: string;
  code?: string;
  message?: string;
}

export interface ErrorEnvelope {
  timestamp: string;
  status: number;
  code: string;
  message: string;
  details?: ErrorDetail[];
  correlationId?: string;
}

export interface ActuatorHealthResponse {
  status: string;
  groups?: string[];
  components?: Record<string, { status: string; details?: Record<string, unknown> }>;
}
