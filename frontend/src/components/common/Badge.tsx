import React from 'react';

interface BadgeProps {
  status: string;
  type?: 'campaign' | 'action' | 'webhook' | 'general';
}

export const Badge: React.FC<BadgeProps> = ({ status, type = 'general' }) => {
  const getStyle = (val: string): string => {
    const s = val?.toUpperCase() || '';
    switch (s) {
      case 'RECOVERED':
      case 'SUCCEEDED':
      case 'VERIFIED':
      case 'PROCESSED':
      case 'SUCCESS':
      case 'PASSED':
      case 'APPROVED':
        return 'bg-emerald-50 text-emerald-700 border-emerald-200';
      case 'CREATED':
      case 'PENDING':
      case 'SCHEDULED':
      case 'RECEIVED':
        return 'bg-blue-50 text-blue-700 border-blue-200';
      case 'CLASSIFIED':
      case 'ELIGIBLE':
      case 'EXECUTING':
      case 'CLAIMED':
      case 'AWAITING_PAYMENT':
      case 'IN_PROGRESS':
        return 'bg-indigo-50 text-indigo-700 border-indigo-200';
      case 'EXHAUSTED':
      case 'FAILED':
      case 'REJECTED':
      case 'ERROR':
        return 'bg-rose-50 text-rose-700 border-rose-200';
      case 'CANCELLED':
      case 'NEEDS_RECONCILIATION':
      case 'DUPLICATE':
      case 'ESCALATED':
      case 'WAITING_FOR_APPROVAL':
      case 'REVIEW_REQUIRED':
      case 'CLOSED':
        return 'bg-amber-50 text-amber-700 border-amber-200';
      default:
        return 'bg-slate-50 text-slate-700 border-slate-200';
    }
  };

  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-[11px] font-bold tracking-wide border transition-colors ${getStyle(
        status
      )}`}
    >
      <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-current opacity-75" />
      {status}
    </span>
  );
};
