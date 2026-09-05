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

export const formatCurrency = (amount: number | undefined | null, currencySymbol = '₹'): string => {
  if (amount === undefined || amount === null || Number.isNaN(amount)) return '—';
  return `${currencySymbol}${amount.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
};

export const formatINR = (amount: number | undefined | null): string => formatCurrency(amount, '₹');

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
        return 'bg-emerald-50 text-emerald-700 border-emerald-200';
      case 'rose':
        return 'bg-rose-50 text-rose-700 border-rose-200';
      case 'indigo':
        return 'bg-blue-50 text-blue-700 border-blue-200';
      case 'amber':
        return 'bg-amber-50 text-amber-700 border-amber-200';
      default:
        return 'bg-blue-50 text-blue-700 border-blue-200';
    }
  };

  if (loading) {
    return (
      <div className="app-surface animate-pulse space-y-3 rounded-xl p-5">
        <div className="h-4 bg-gray-200 dark:bg-gray-800 rounded w-1/2" />
        <div className="h-8 bg-gray-200 dark:bg-gray-800 rounded w-3/4" />
        <div className="h-3 bg-gray-200 dark:bg-gray-800 rounded w-2/3" />
      </div>
    );
  }

  return (
    <div className="app-surface relative overflow-hidden rounded-xl p-5 transition-all duration-200">
      <div className="flex items-center justify-between">
        <span className="app-kicker">{title}</span>
        {icon && (
          <div className={`flex h-9 w-9 items-center justify-center rounded-lg border ${getBadgeStyle()}`}>
            {icon}
          </div>
        )}
      </div>

      <div className="mt-3 flex items-baseline justify-between">
        <div className="text-[1.8rem] font-black tracking-tight text-[var(--navy)] font-mono">
          {value}
        </div>
        {trend && (
          <span
            className={`text-xs font-bold px-2 py-0.5 rounded-md border ${
              trend.positive !== false
                ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                : 'bg-rose-50 text-rose-700 border-rose-200'
            }`}
          >
            {trend.value}
          </span>
        )}
      </div>

      {subtext && (
        <p className="mt-2 text-xs font-medium text-[var(--ink-muted)]">
          {subtext}
        </p>
      )}
    </div>
  );
};
