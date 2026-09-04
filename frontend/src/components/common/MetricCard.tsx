import React from 'react';

interface MetricCardProps {
  title: string;
  value: string | number;
  subtext?: string;
  trend?: {
    value: string;
    positive?: boolean;
  };
  icon?: React.ReactNode;
  loading?: boolean;
  variant?: 'default' | 'highlight' | 'emerald' | 'rose' | 'indigo' | 'amber';
}

export const formatCurrency = (amount: number | undefined | null, currencySymbol = '$'): string => {
  if (amount === undefined || amount === null) return `${currencySymbol}0.00`;
  return `${currencySymbol}${amount.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
};

export const MetricCard: React.FC<MetricCardProps> = ({
  title,
  value,
  subtext,
  trend,
  icon,
  loading = false,
  variant = 'default',
}) => {
  const getBadgeStyle = () => {
    switch (variant) {
      case 'emerald':
        return 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/50 dark:text-emerald-400 dark:border-emerald-800';
      case 'rose':
        return 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/50 dark:text-rose-400 dark:border-rose-800';
      case 'indigo':
        return 'bg-indigo-50 text-indigo-700 border-indigo-200 dark:bg-indigo-950/50 dark:text-indigo-400 dark:border-indigo-800';
      case 'amber':
        return 'bg-amber-50 text-amber-700 border-amber-200 dark:bg-amber-950/50 dark:text-amber-400 dark:border-amber-800';
      default:
        return 'bg-blue-50 text-blue-700 border-blue-200 dark:bg-blue-950/50 dark:text-blue-400 dark:border-blue-800';
    }
  };

  if (loading) {
    return (
      <div className="bg-white dark:bg-gray-900 p-6 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm animate-pulse space-y-3">
        <div className="h-4 bg-gray-200 dark:bg-gray-800 rounded w-1/2" />
        <div className="h-8 bg-gray-200 dark:bg-gray-800 rounded w-3/4" />
        <div className="h-3 bg-gray-200 dark:bg-gray-800 rounded w-2/3" />
      </div>
    );
  }

  return (
    <div className="bg-white dark:bg-gray-900 p-6 rounded-2xl border border-gray-200 dark:border-gray-800 shadow-sm relative overflow-hidden transition-all duration-200 hover:shadow-md">
      <div className="flex items-center justify-between">
        <span className="text-xs font-bold uppercase tracking-wider text-gray-500 dark:text-gray-400">{title}</span>
        {icon && (
          <div className={`w-9 h-9 rounded-xl flex items-center justify-center border ${getBadgeStyle()}`}>
            {icon}
          </div>
        )}
      </div>

      <div className="mt-3 flex items-baseline justify-between">
        <div className="text-3xl font-black tracking-tight text-gray-900 dark:text-white font-mono">
          {value}
        </div>
        {trend && (
          <span
            className={`text-xs font-bold px-2 py-0.5 rounded-md border ${
              trend.positive !== false
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200 dark:bg-emerald-950/50 dark:text-emerald-400 dark:border-emerald-800'
                : 'bg-rose-50 text-rose-700 border-rose-200 dark:bg-rose-950/50 dark:text-rose-400 dark:border-rose-800'
            }`}
          >
            {trend.value}
          </span>
        )}
      </div>

      {subtext && (
        <p className="text-xs text-gray-500 dark:text-gray-400 mt-2 font-medium">
          {subtext}
        </p>
      )}
    </div>
  );
};
