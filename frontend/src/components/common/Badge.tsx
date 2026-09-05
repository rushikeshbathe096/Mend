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
        return 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/40 dark:text-emerald-400 dark:border-emerald-800';
      case 'CREATED':
      case 'PENDING':
      case 'SCHEDULED':
      case 'RECEIVED':
      case 'PENDING_REVIEW':
        return 'bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-950/40 dark:text-blue-400 dark:border-blue-800';
      case 'CLASSIFIED':
      case 'ELIGIBLE':
      case 'EXECUTING':
      case 'CLAIMED':
      case 'IN_REVIEW':
      case 'IN_PROGRESS':
        return 'bg-indigo-50 text-indigo-700 border-indigo-200 dark:bg-indigo-950/40 dark:text-indigo-400 dark:border-indigo-800';
      case 'EXHAUSTED':
      case 'FAILED':
      case 'REJECTED':
      case 'OVERRIDDEN':
      case 'ERROR':
        return 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/40 dark:text-rose-400 dark:border-rose-800';
      case 'CANCELLED':
      case 'IGNORED':
      case 'DUPLICATE':
      case 'ESCALATED':
      case 'WAITING_FOR_APPROVAL':
      case 'REVIEW_REQUIRED':
      case 'CLOSED':
        return 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/40 dark:text-amber-400 dark:border-amber-800';
      default:
        return 'bg-gray-50 text-gray-700 border-gray-200 dark:bg-gray-800 dark:text-gray-300 dark:border-gray-700';
    }
  };

  return (
    <span
      className={`inline-flex items-center px-2.5 py-0.5 rounded-full text-xs font-semibold border transition-colors ${getStyle(
        status
      )}`}
    >
      <span className="w-1.5 h-1.5 rounded-full mr-1.5 bg-current opacity-75" />
      {status}
    </span>
  );
};
