import React from 'react';
import { Inbox } from 'lucide-react';

interface EmptyStateProps {
  title?: string;
  message?: string;
  icon?: React.ReactNode;
}

export const EmptyState: React.FC<EmptyStateProps> = ({
  title = 'No records found',
  message = 'There is currently no simulated data matching your criteria.',
  icon,
}) => {
  return (
    <div className="empty-state" role="status">
      <div className="empty-state-icon">
        {icon || <Inbox size={38} color="var(--text-muted)" />}
      </div>
      <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--text-secondary)', marginBottom: '4px' }}>
        {title}
      </h3>
      <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
        {message}
      </p>
    </div>
  );
};
