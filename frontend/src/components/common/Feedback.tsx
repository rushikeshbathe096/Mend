import React from 'react';

export const LoadingSpinner: React.FC<{ message?: string }> = ({ message = 'Loading data...' }) => {
  return (
    <div className="flex flex-col items-center justify-center space-y-3 p-12">
      <div className="h-7 w-7 animate-spin rounded-full border-2 border-slate-200 border-t-blue-600" />
      <p className="text-sm font-medium text-[var(--ink-muted)]">{message}</p>
    </div>
  );
};

export const EmptyState: React.FC<{ title: string; description: string; actionLabel?: string; onAction?: () => void }> = ({
  title,
  description,
  actionLabel,
  onAction,
}) => {
  return (
    <div className="my-4 flex flex-col items-center justify-center rounded-xl border border-dashed border-[var(--border-strong)] bg-[var(--surface-subtle)] p-12 text-center">
      <div className="mb-4 flex h-11 w-11 items-center justify-center rounded-lg bg-blue-50 text-blue-600">
        <svg className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M20 13V6a2 2 0 00-2-2H6a2 2 0 00-2 2v7m16 0v5a2 2 0 01-2 2H6a2 2 0 01-2-2v-5m16 0h-2.586a1 1 0 00-.707.293l-2.414 2.414a1 1 0 01-.707.293h-3.172a1 1 0 01-.707-.293l-2.414-2.414A1 1 0 006.586 13H4" />
        </svg>
      </div>
      <h3 className="mb-1 text-base font-bold text-[var(--navy)]">{title}</h3>
      <p className="mb-6 max-w-md text-sm text-[var(--ink-muted)]">{description}</p>
      {actionLabel && onAction && (
        <button
          onClick={onAction}
          className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white hover:-translate-y-px hover:bg-blue-700"
        >
          {actionLabel}
        </button>
      )}
    </div>
  );
};

export const ErrorAlert: React.FC<{ message: string; onRetry?: () => void }> = ({ message, onRetry }) => {
  return (
    <div role="alert" className="my-4 flex items-center justify-between gap-4 rounded-xl border border-rose-200 bg-rose-50 p-4 text-rose-800">
      <div className="flex items-center space-x-3">
        <svg className="h-5 w-5 flex-shrink-0 text-rose-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" aria-hidden="true">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z" />
        </svg>
        <span className="text-sm font-medium">{message}</span>
      </div>
      {onRetry && (
        <button
          onClick={onRetry}
          className="shrink-0 rounded-md bg-rose-600 px-3 py-1.5 text-xs font-semibold text-white hover:bg-rose-700"
        >
          Retry
        </button>
      )}
    </div>
  );
};
