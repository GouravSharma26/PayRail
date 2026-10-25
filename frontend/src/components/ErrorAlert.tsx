import React from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { ApiError } from '../api/client';

interface ErrorAlertProps {
  error: Error | ApiError | string | null;
  onRetry?: () => void;
  title?: string;
}

export const ErrorAlert: React.FC<ErrorAlertProps> = ({ error, onRetry, title = 'Operation Failed' }) => {
  if (!error) return null;

  const message = typeof error === 'string' ? error : error.message;
  const isApiError = error instanceof ApiError;
  const correlationId = isApiError ? error.correlationId : undefined;
  const code = isApiError ? error.code : undefined;

  return (
    <div className="error-alert" role="alert">
      <AlertCircle size={20} style={{ flexShrink: 0, marginTop: '2px' }} />
      <div style={{ flex: 1 }}>
        <div style={{ fontWeight: 700, marginBottom: '2px' }}>{title}</div>
        <div>{message}</div>
        {(code || correlationId) && (
          <div style={{ marginTop: '6px', fontSize: '0.75rem', opacity: 0.85, fontFamily: 'var(--font-mono)' }}>
            {code && <span>Code: {code} </span>}
            {correlationId && <span>| Correlation: {correlationId}</span>}
          </div>
        )}
      </div>
      {onRetry && (
        <button
          className="btn btn-secondary"
          onClick={onRetry}
          style={{ fontSize: '0.75rem', padding: '4px 8px' }}
        >
          <RefreshCw size={12} /> Retry
        </button>
      )}
    </div>
  );
};
