import React from 'react';

export const TableSkeleton: React.FC<{ rows?: number; columns?: number }> = ({ rows = 5, columns = 5 }) => {
  return (
    <div className="w-full bg-white dark:bg-gray-900 rounded-2xl border border-gray-200 dark:border-gray-800 p-4 space-y-4 animate-pulse">
      <div className="h-6 bg-gray-200 dark:bg-gray-800 rounded w-1/4 mb-4" />
      {Array.from({ length: rows }).map((_, rIdx) => (
        <div key={rIdx} className="flex space-x-4 py-2 border-b border-gray-100 dark:border-gray-800 last:border-0">
          {Array.from({ length: columns }).map((_, cIdx) => (
            <div key={cIdx} className="h-4 bg-gray-200 dark:bg-gray-800 rounded flex-1" />
          ))}
        </div>
      ))}
    </div>
  );
};

export const CardSkeleton: React.FC = () => {
  return (
    <div className="bg-white dark:bg-gray-900 p-6 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm animate-pulse space-y-4">
      <div className="flex justify-between items-center">
        <div className="h-4 bg-gray-200 dark:bg-gray-800 rounded w-1/3" />
        <div className="w-8 h-8 bg-gray-200 dark:bg-gray-800 rounded-lg" />
      </div>
      <div className="h-8 bg-gray-200 dark:bg-gray-800 rounded w-1/2" />
      <div className="h-3 bg-gray-200 dark:bg-gray-800 rounded w-2/3" />
    </div>
  );
};

export const DetailSkeleton: React.FC = () => {
  return (
    <div className="space-y-6 animate-pulse">
      <div className="h-8 bg-gray-200 dark:bg-gray-800 rounded w-1/3" />
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <CardSkeleton />
        <CardSkeleton />
        <CardSkeleton />
      </div>
      <div className="h-64 bg-gray-200 dark:bg-gray-800 rounded-2xl" />
    </div>
  );
};
