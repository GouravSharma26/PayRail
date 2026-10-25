import React from 'react';
import { ChevronLeft, ChevronRight, ChevronsLeft, ChevronsRight } from 'lucide-react';

interface PaginationProps {
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
  onPageChange: (newPage: number) => void;
  disabled?: boolean;
}

export const Pagination: React.FC<PaginationProps> = ({
  page,
  size,
  totalItems,
  totalPages,
  onPageChange,
  disabled = false,
}) => {
  if (totalItems === 0) return null;

  const startItem = page * size + 1;
  const endItem = Math.min((page + 1) * size, totalItems);

  return (
    <div className="pagination" role="navigation" aria-label="Pagination">
      <div className="pagination-info">
        Showing <span className="font-mono">{startItem}</span> to{' '}
        <span className="font-mono">{endItem}</span> of{' '}
        <span className="font-mono">{totalItems}</span> results
      </div>

      <div className="pagination-controls">
        <button
          className="btn-icon"
          onClick={() => onPageChange(0)}
          disabled={disabled || page === 0}
          aria-label="First page"
          title="First page"
        >
          <ChevronsLeft size={16} />
        </button>
        <button
          className="btn-icon"
          onClick={() => onPageChange(page - 1)}
          disabled={disabled || page === 0}
          aria-label="Previous page"
          title="Previous page"
        >
          <ChevronLeft size={16} />
        </button>

        <span style={{ margin: '0 8px', fontSize: '0.85rem' }}>
          Page <strong className="font-mono">{page + 1}</strong> of{' '}
          <strong className="font-mono">{Math.max(1, totalPages)}</strong>
        </span>

        <button
          className="btn-icon"
          onClick={() => onPageChange(page + 1)}
          disabled={disabled || page >= totalPages - 1}
          aria-label="Next page"
          title="Next page"
        >
          <ChevronRight size={16} />
        </button>
        <button
          className="btn-icon"
          onClick={() => onPageChange(totalPages - 1)}
          disabled={disabled || page >= totalPages - 1}
          aria-label="Last page"
          title="Last page"
        >
          <ChevronsRight size={16} />
        </button>
      </div>
    </div>
  );
};
