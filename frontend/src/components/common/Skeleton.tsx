import React from 'react';

export const TableSkeleton: React.FC<{ rows?: number; columns?: number }> = ({ rows = 5, columns = 5 }) => {
  return (
    <div className="app-surface w-full space-y-4 rounded-xl p-4 animate-pulse">
      <div className="mb-4 h-5 w-1/4 rounded bg-slate-200" />
      {Array.from({ length: rows }).map((_, rIdx) => (
        <div key={rIdx} className="flex space-x-4 border-b border-[var(--border)] py-2 last:border-0">
          {Array.from({ length: columns }).map((_, cIdx) => (
            <div key={cIdx} className="h-3 flex-1 rounded bg-slate-200" />
          ))}
        </div>
      ))}
    </div>
  );
};

export const CardSkeleton: React.FC = () => {
  return (
    <div className="app-surface animate-pulse space-y-4 rounded-xl p-5">
      <div className="flex justify-between items-center">
        <div className="h-3 w-1/3 rounded bg-slate-200" />
        <div className="h-8 w-8 rounded-lg bg-slate-200" />
      </div>
      <div className="h-8 w-1/2 rounded bg-slate-200" />
      <div className="h-3 w-2/3 rounded bg-slate-200" />
    </div>
  );
};

export const DetailSkeleton: React.FC = () => {
  return (
    <div className="space-y-6 animate-pulse">
      <div className="h-8 w-1/3 rounded bg-slate-200" />
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <CardSkeleton />
        <CardSkeleton />
        <CardSkeleton />
      </div>
      <div className="h-64 rounded-xl bg-slate-200" />
    </div>
  );
};
