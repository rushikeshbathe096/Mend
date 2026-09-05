'use client';

import React from 'react';

const FLOW = ['FAILED', 'CLASSIFIED', 'ELIGIBLE', 'ACTION_PENDING', 'EXECUTING', 'RECOVERED'];
const REVIEW_FLOW = ['FAILED', 'REVIEW_REQUIRED', 'HUMAN_APPROVAL', 'APPROVED', 'EXECUTING', 'RECOVERED'];

interface RecoveryLifecycleProps {
  currentState: string;
  hasReview?: boolean;
  onStateSelect?: (state: string) => void;
}

function normalizeState(state: string) {
  if (state === 'CREATED') return 'FAILED';
  if (state === 'CLASSIFIED') return 'CLASSIFIED';
  if (state === 'BLOCKED' || state === 'EXHAUSTED' || state === 'FAILED') return 'FAILED';
  return state;
}

export const RecoveryLifecycle: React.FC<RecoveryLifecycleProps> = ({ currentState, hasReview = false, onStateSelect }) => {
  const flow = hasReview || currentState === 'REVIEW_REQUIRED' ? REVIEW_FLOW : FLOW;
  const normalized = normalizeState(currentState);
  const currentIndex = flow.indexOf(normalized);
  const activeIndex = currentIndex >= 0 ? currentIndex : currentState === 'RECOVERED' ? flow.length - 1 : 0;

  return (
    <section aria-label="Recovery lifecycle" className="rounded-2xl border border-gray-200 bg-white p-5 shadow-sm dark:border-gray-800 dark:bg-gray-900">
      <div className="mb-5 flex items-start justify-between gap-4">
        <div>
          <h3 className="text-base font-bold text-gray-900 dark:text-white">Recovery lifecycle</h3>
          <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">Derived from the campaign state and review records.</p>
        </div>
        <span className="rounded-md border border-blue-200 bg-blue-50 px-2 py-1 text-[10px] font-bold uppercase tracking-wide text-blue-700">{currentState.replace(/_/g, ' ')}</span>
      </div>
      <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-6">
        {flow.map((state, index) => {
          const isComplete = index < activeIndex;
          const isCurrent = index === activeIndex;
          const isLast = index === flow.length - 1;
          return (
            <React.Fragment key={state}>
              <button
                type="button"
                onClick={() => onStateSelect?.(state)}
                className={`group relative rounded-xl border p-3 text-left transition duration-200 ${
                  isCurrent ? 'border-blue-300 bg-blue-50 shadow-sm dark:border-blue-700 dark:bg-blue-950/30' : isComplete ? 'border-emerald-200 bg-emerald-50/60 dark:border-emerald-900 dark:bg-emerald-950/20' : 'border-gray-200 bg-gray-50/60 dark:border-gray-800 dark:bg-gray-800/30'
                } ${onStateSelect ? 'cursor-pointer hover:-translate-y-0.5' : 'cursor-default'}`}
                aria-current={isCurrent ? 'step' : undefined}
              >
                <div className={`mb-2 flex h-7 w-7 items-center justify-center rounded-full text-xs font-black ${isCurrent ? 'bg-blue-600 text-white' : isComplete ? 'bg-emerald-600 text-white' : 'bg-gray-200 text-gray-500 dark:bg-gray-700 dark:text-gray-400'}`}>
                  {isComplete ? '✓' : index + 1}
                </div>
                <div className={`text-[10px] font-bold uppercase tracking-wide ${isCurrent ? 'text-blue-700 dark:text-blue-300' : isComplete ? 'text-emerald-700 dark:text-emerald-300' : 'text-gray-500'}`}>{state.replace(/_/g, ' ')}</div>
                <div className="mt-1 text-[10px] text-gray-500">{isCurrent ? 'Current state' : isComplete ? 'Completed' : 'Awaiting state'}</div>
              </button>
              {!isLast && <div aria-hidden="true" className="hidden items-center justify-center text-gray-300 sm:flex lg:hidden">→</div>}
            </React.Fragment>
          );
        })}
      </div>
    </section>
  );
};
