import React from 'react';
import { TransferStatus, AccountStatus } from '../types/api';

interface StatusBadgeProps {
  status: TransferStatus | AccountStatus | string;
  pulse?: boolean;
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ status, pulse = false }) => {
  const normalized = status ? status.toUpperCase() : 'UNKNOWN';

  let badgeClass = 'badge-neutral';
  if (['SUCCEEDED', 'ACTIVE', 'CLEAN', 'UP', 'HEALTHY'].includes(normalized)) {
    badgeClass = 'badge-success';
  } else if (['PENDING', 'PROCESSING', 'RETRY_SCHEDULED', 'DEGRADED'].includes(normalized)) {
    badgeClass = 'badge-warning';
  } else if (['FAILED', 'INACTIVE', 'DOWN', 'CRITICAL', 'DISCREPANCIES_DETECTED', 'SCHEMA_UNSUPPORTED', 'DESERIALIZATION_ERROR', 'INVALID_ENVELOPE'].includes(normalized)) {
    badgeClass = 'badge-danger';
  } else if (['HIGH'].includes(normalized)) {
    badgeClass = 'badge-warning';
  } else if (['MEDIUM', 'LOW'].includes(normalized)) {
    badgeClass = 'badge-info';
  }

  return (
    <span className={`badge ${badgeClass}`} role="status" aria-label={normalized}>
      {pulse && <span className="pulse-dot" aria-hidden="true" />}
      {normalized}
    </span>
  );
};
