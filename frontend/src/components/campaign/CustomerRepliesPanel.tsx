'use client';

import React, { useState } from 'react';
import { api } from '@/lib/api';
import { errorMessage } from '@/lib/errors';
import { useLoad } from '@/lib/useLoad';
import { CustomerReplyDto } from '@/types';

interface CustomerRepliesPanelProps {
  campaignId: string;
  merchantId: string | null;
  optedOutAt?: string | null;
  canClearOptOut: boolean;
  /** Called after any change, so the parent can refetch the campaign (e.g. a new opt-out from a parsed reply). */
  onChanged?: () => void;
}

const EFFECT_LABELS: Record<string, string> = {
  NONE: 'No effect',
  SHOWN_ONLY: 'Shown only',
  CONTACT_OPTED_OUT: 'Contact stopped (opt-out recorded)',
  ALREADY_OPTED_OUT: 'Already opted out',
  PAYMENT_LOOKUP_BROUGHT_FORWARD: 'Payment status check run earlier (not marked paid)',
  REVIEW_CUSTOMER_SAYS_PAID: 'Sent to review: customer says paid',
  REVIEW_CUSTOMER_DISPUTE: 'Sent to review: dispute',
};

/**
 * M3 (ADR-003 §5): customer replies and the model's *suggestions*. A suggestion never moves money; the effect shown is
 * the deterministic policy's. Replies are customer PII and are shown only to the merchant's own users.
 */
export const CustomerRepliesPanel: React.FC<CustomerRepliesPanelProps> = ({ campaignId, merchantId, optedOutAt, canClearOptOut, onChanged }) => {
  const [replies, setReplies] = useState<CustomerReplyDto[]>([]);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const { reload } = useLoad(merchantId ? `${merchantId}|${campaignId}` : null, async (isCurrent) => {
    try {
      const data = await api.get<CustomerReplyDto[]>(`/campaigns/${campaignId}/replies`, { merchantId });
      if (isCurrent()) {
        setReplies(data || []);
        setError(null);
      }
    } catch (err: unknown) {
      if (isCurrent()) setError(errorMessage(err, 'Failed to load customer replies.'));
    }
  });

  const run = async (action: () => Promise<unknown>, fallback: string) => {
    setBusy(true);
    try {
      await action();
      setError(null);
      reload();
      onChanged?.();
    } catch (err: unknown) {
      setError(errorMessage(err, fallback));
    } finally {
      setBusy(false);
    }
  };

  const submit = () =>
    run(async () => {
      await api.post(`/campaigns/${campaignId}/replies`, { text }, { merchantId });
      setText('');
    }, 'Failed to record the reply.');

  return (
    <div className="space-y-4" data-testid="customer-replies">
      {optedOutAt && (
        <div className="flex items-center justify-between gap-3 p-3 rounded-xl border border-rose-200 bg-rose-50 dark:bg-rose-950/30 dark:border-rose-800 text-xs">
          <span className="font-semibold text-rose-800 dark:text-rose-300">
            Customer opted out of contact ({new Date(optedOutAt).toLocaleString()}). Compliance blocks new recovery actions and pending ones at dispatch; a payment link already sent can still be paid.
          </span>
          {canClearOptOut && (
            <button
              type="button"
              disabled={busy}
              onClick={() => run(async () => {
                await api.delete(`/campaigns/${campaignId}/opt-out`, { merchantId });
              }, 'Failed to clear the opt-out.')}
              className="px-3 py-1.5 rounded-lg border border-rose-300 text-rose-800 dark:text-rose-200 font-bold disabled:opacity-50"
            >
              Clear opt-out
            </button>
          )}
        </div>
      )}

      <div className="space-y-2">
        <label htmlFor="customer-reply-text" className="block text-xs font-bold text-gray-700 dark:text-gray-300 uppercase tracking-wider">
          Record a customer reply
        </label>
        <textarea
          id="customer-reply-text"
          value={text}
          maxLength={2000}
          rows={3}
          onChange={(e) => setText(e.target.value)}
          className="w-full rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-900 p-3 text-sm"
        />
        <div className="flex items-center justify-between text-[11px] text-gray-500">
          <span>Parsed asynchronously into a suggestion. It is customer data: stored for 90 days.</span>
          <button
            type="button"
            disabled={busy || text.trim().length === 0}
            onClick={submit}
            className="px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white rounded-xl text-xs font-bold"
          >
            Record reply
          </button>
        </div>
      </div>

      {error && <p className="text-xs text-rose-600" role="alert">{error}</p>}

      <div className="flex justify-end">
        <button type="button" onClick={() => { reload(); onChanged?.(); }} className="text-[11px] font-bold text-blue-600 hover:underline">
          Refresh
        </button>
      </div>

      {replies.length === 0 ? (
        <p className="text-sm text-gray-500 italic py-4 text-center">No customer replies recorded for this campaign.</p>
      ) : (
        <ul className="space-y-3">
          {replies.map((reply) => (
            <li key={reply.id} className="p-4 rounded-xl border border-gray-200 dark:border-gray-800 space-y-2 text-xs">
              <p className="text-sm text-gray-800 dark:text-gray-200 whitespace-pre-wrap break-words">{reply.text}</p>
              <div className="flex flex-wrap gap-x-4 gap-y-1 text-gray-500">
                <span>Status: <span className="font-mono font-bold">{reply.status}</span></span>
                <span>{new Date(reply.createdAt).toLocaleString()}</span>
                {reply.lastError && <span>Last error: <span className="font-mono">{reply.lastError}</span></span>}
              </div>
              {reply.suggestion && (
                <div className="rounded-lg bg-gray-50 dark:bg-gray-800/60 p-3 space-y-1">
                  <div className="font-bold text-gray-700 dark:text-gray-300">Model suggestion (not a decision)</div>
                  {reply.suggestion.valid ? (
                    <div>
                      Intent: <span className="font-mono font-bold">{reply.suggestion.intent}</span>
                      {reply.suggestion.promisedDate && <> · promised date {reply.suggestion.promisedDate}</>}
                      {reply.suggestion.abstain && <> · the model abstained</>}
                    </div>
                  ) : (
                    <div className="text-amber-700 dark:text-amber-400">
                      Invalid model output (<span className="font-mono">{reply.suggestion.validationError}</span>); ignored.
                    </div>
                  )}
                  <div>Effect: {EFFECT_LABELS[reply.suggestion.effect] ?? reply.suggestion.effect}</div>
                  <div className="text-gray-400">
                    Model: <span className="font-mono">{reply.suggestion.modelId ?? 'not reported'}</span>
                    {reply.suggestion.promptVersion && <> · prompt {reply.suggestion.promptVersion}</>}
                  </div>
                </div>
              )}
              {reply.status === 'FAILED' && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={() => run(() => api.post(`/campaigns/${campaignId}/replies/${reply.id}/retry`, undefined, { merchantId }),
                    'Failed to retry the reply.')}
                  className="px-3 py-1.5 rounded-lg border border-gray-300 dark:border-gray-700 font-bold disabled:opacity-50"
                >
                  Retry parsing
                </button>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
};
