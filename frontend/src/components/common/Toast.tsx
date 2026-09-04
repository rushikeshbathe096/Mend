import React, { useEffect } from 'react';

interface ToastProps {
  message: string;
  type?: 'success' | 'error' | 'info';
  onClose: () => void;
  durationMs?: number;
}

export const Toast: React.FC<ToastProps> = ({
  message,
  type = 'success',
  onClose,
  durationMs = 4000,
}) => {
  useEffect(() => {
    const timer = setTimeout(onClose, durationMs);
    return () => clearTimeout(timer);
  }, [onClose, durationMs]);

  const styles = {
    success: 'bg-emerald-900/90 text-emerald-100 border-emerald-700',
    error: 'bg-rose-900/90 text-rose-100 border-rose-700',
    info: 'bg-blue-900/90 text-blue-100 border-blue-700',
  };

  return (
    <div className="fixed bottom-6 right-6 z-50 animate-bounce">
      <div
        className={`flex items-center space-x-3 px-4 py-3 rounded-xl border shadow-xl text-xs font-semibold backdrop-blur ${styles[type]}`}
      >
        <span>{message}</span>
        <button onClick={onClose} className="hover:opacity-75 font-bold">
          &times;
        </button>
      </div>
    </div>
  );
};
