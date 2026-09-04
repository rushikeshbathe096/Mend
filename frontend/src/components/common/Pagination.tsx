import React from 'react';

interface PaginationProps {
  currentPage: number;
  totalPages: number;
  totalElements?: number;
  pageSize?: number;
  onPageChange: (page: number) => void;
}

export const Pagination: React.FC<PaginationProps> = ({
  currentPage,
  totalPages,
  totalElements = 0,
  pageSize = 10,
  onPageChange,
}) => {
  if (totalPages <= 1 && totalElements <= pageSize) {
    return null;
  }

  const start = currentPage * pageSize + 1;
  const end = Math.min((currentPage + 1) * pageSize, totalElements);

  return (
    <div className="flex items-center justify-between px-4 py-3 border-t border-gray-100 dark:border-gray-800 sm:px-6">
      <div className="text-sm text-gray-500 dark:text-gray-400">
        Showing <span className="font-semibold text-gray-900 dark:text-white">{totalElements > 0 ? start : 0}</span> to{' '}
        <span className="font-semibold text-gray-900 dark:text-white">{end}</span> of{' '}
        <span className="font-semibold text-gray-900 dark:text-white">{totalElements}</span> results
      </div>
      <div className="flex items-center space-x-2">
        <button
          onClick={() => onPageChange(currentPage - 1)}
          disabled={currentPage === 0}
          className="px-3 py-1.5 border border-gray-300 dark:border-gray-700 rounded-md text-xs font-medium text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          Previous
        </button>
        <span className="text-xs text-gray-500 dark:text-gray-400 px-1">
          Page {currentPage + 1} of {Math.max(1, totalPages)}
        </span>
        <button
          onClick={() => onPageChange(currentPage + 1)}
          disabled={currentPage >= totalPages - 1}
          className="px-3 py-1.5 border border-gray-300 dark:border-gray-700 rounded-md text-xs font-medium text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        >
          Next
        </button>
      </div>
    </div>
  );
};
