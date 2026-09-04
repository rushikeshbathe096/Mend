import React from 'react';
import { Badge } from '@/components/common/Badge';

export interface TimelineStep {
  id: string;
  title: string;
  subtitle?: string;
  timestamp?: string;
  status: 'COMPLETED' | 'IN_PROGRESS' | 'PENDING' | 'FAILED' | 'BLOCKED';
  icon?: React.ReactNode;
  details?: React.ReactNode;
}

interface TimelineProps {
  steps: TimelineStep[];
}

export const Timeline: React.FC<TimelineProps> = ({ steps }) => {
  const getStepColor = (status: TimelineStep['status']) => {
    switch (status) {
      case 'COMPLETED':
        return 'bg-emerald-600 text-white ring-emerald-100 dark:ring-emerald-950/50';
      case 'IN_PROGRESS':
        return 'bg-blue-600 text-white ring-blue-100 dark:ring-blue-950/50 animate-pulse';
      case 'FAILED':
        return 'bg-rose-600 text-white ring-rose-100 dark:ring-rose-950/50';
      case 'BLOCKED':
        return 'bg-amber-600 text-white ring-amber-100 dark:ring-amber-950/50';
      default:
        return 'bg-gray-300 dark:bg-gray-700 text-gray-600 dark:text-gray-400 ring-gray-100 dark:ring-gray-800';
    }
  };

  return (
    <div className="relative pl-6 space-y-8 before:absolute before:left-3.5 before:top-2 before:bottom-2 before:w-0.5 before:bg-gray-200 dark:before:bg-gray-800">
      {steps.map((step, idx) => (
        <div key={step.id || idx} className="relative flex items-start space-x-4 group">
          {/* Circle Icon */}
          <div
            className={`absolute -left-6 top-1.5 w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold ring-4 transition-transform group-hover:scale-110 ${getStepColor(
              step.status
            )}`}
          >
            {step.icon || idx + 1}
          </div>

          {/* Card Content */}
          <div className="flex-1 bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-2xl p-5 shadow-sm space-y-3">
            <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-1">
              <div>
                <h4 className="text-sm font-bold text-gray-900 dark:text-white flex items-center space-x-2">
                  <span>{step.title}</span>
                </h4>
                {step.subtitle && (
                  <p className="text-xs text-gray-500 dark:text-gray-400 mt-0.5">{step.subtitle}</p>
                )}
              </div>
              <div className="flex items-center space-x-2">
                <Badge status={step.status} />
                {step.timestamp && (
                  <span className="text-[11px] font-mono text-gray-400 dark:text-gray-500 whitespace-nowrap">
                    {new Date(step.timestamp).toLocaleString()}
                  </span>
                )}
              </div>
            </div>

            {step.details && (
              <div className="pt-3 border-t border-gray-100 dark:border-gray-800 text-xs text-gray-600 dark:text-gray-300">
                {step.details}
              </div>
            )}
          </div>
        </div>
      ))}
    </div>
  );
};
