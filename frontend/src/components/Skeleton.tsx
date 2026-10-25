import React from 'react';

interface SkeletonProps {
  height?: string | number;
  width?: string | number;
  style?: React.CSSProperties;
}

export const Skeleton: React.FC<SkeletonProps> = ({ height = '1rem', width = '100%', style }) => {
  return (
    <div
      className="skeleton"
      style={{
        height,
        width,
        ...style,
      }}
      aria-hidden="true"
    />
  );
};

export const TableSkeleton: React.FC<{ rows?: number; columns?: number }> = ({ rows = 5, columns = 5 }) => {
  return (
    <>
      {Array.from({ length: rows }).map((_, rIdx) => (
        <tr key={rIdx}>
          {Array.from({ length: columns }).map((_, cIdx) => (
            <td key={cIdx}>
              <Skeleton height="1.1rem" width={cIdx === 0 ? '70%' : '90%'} />
            </td>
          ))}
        </tr>
      ))}
    </>
  );
};

export const CardSkeleton: React.FC<{ count?: number }> = ({ count = 3 }) => {
  return (
    <div className="grid grid-cols-3" style={{ gap: '1rem', marginBottom: '1.5rem' }}>
      {Array.from({ length: count }).map((_, idx) => (
        <div key={idx} className="card">
          <Skeleton height="1rem" width="40%" style={{ marginBottom: '0.75rem' }} />
          <Skeleton height="2rem" width="70%" style={{ marginBottom: '0.5rem' }} />
          <Skeleton height="0.8rem" width="90%" />
        </div>
      ))}
    </div>
  );
};
