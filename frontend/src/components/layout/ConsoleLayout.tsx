'use client';

import React from 'react';
import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';
import { api } from '@/lib/api';
import { ReviewQueueSummaryDto } from '@/types';

interface ConsoleLayoutProps {
  children: React.ReactNode;
}

interface NavItem {
  label: string;
  href: string;
  icon: string;
  adminOnly?: boolean;
}

export const ConsoleLayout: React.FC<ConsoleLayoutProps> = ({ children }) => {
  const pathname = usePathname();
  const router = useRouter();
  const { user, token, currentMerchantId, currentMerchantName, isLoading, logout, selectMerchant } = useAuth();
  const [pendingReviews, setPendingReviews] = React.useState<number>(0);

  const currentRole = user?.memberships?.find((m) => m.merchantId === currentMerchantId)?.roleName || '';
  const isAdmin = currentRole === 'MERCHANT_ADMIN' || currentRole === 'SYSTEM_ADMIN' || user?.memberships?.some((m) => m.roleName === 'SYSTEM_ADMIN');

  React.useEffect(() => {
    if (!isLoading && !token) {
      router.push('/login');
    }
  }, [isLoading, token, router]);

  React.useEffect(() => {
    if (!token || !currentMerchantId || !isAdmin) {
      setPendingReviews(0);
      return;
    }
    let active = true;
    api
      .get<ReviewQueueSummaryDto>('/reviews/summary')
      .then((summary) => {
        if (active) setPendingReviews(summary.pending ?? 0);
      })
      .catch(() => {
        if (active) setPendingReviews(0);
      });
    return () => {
      active = false;
    };
  }, [token, currentMerchantId, isAdmin, pathname]);

  if (isLoading || !token) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-950">
        <div className="flex flex-col items-center space-y-3">
          <div className="w-10 h-10 border-4 border-blue-600 border-t-transparent rounded-full animate-spin" />
          <p className="text-sm text-gray-500 font-medium">Authenticating console session...</p>
        </div>
      </div>
    );
  }

  const navItems: NavItem[] = [
    { label: 'Overview', href: '/dashboard', icon: 'M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6' },
    { label: 'Payments', href: '/payments', icon: 'M3 10h18M7 15h2m4 0h4M5 5h14a2 2 0 012 2v10a2 2 0 01-2 2H5a2 2 0 01-2-2V7a2 2 0 012-2z' },
    { label: 'Campaigns', href: '/campaigns', icon: 'M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10' },
    { label: 'Recovery Actions', href: '/actions', icon: 'M13 10V3L4 14h7v7l9-11h-7z' },
    { label: 'Customers', href: '/customers', icon: 'M16 21v-2a4 4 0 00-4-4H6a4 4 0 00-4 4v2m8-8a4 4 0 100-8 4 4 0 000 8zm6-3a4 4 0 010 8m4 3v-2a4 4 0 00-3-3.87' },
    { label: 'Webhooks', href: '/webhooks', icon: 'M15 17h5l-1.405-1.405A2.032 2.032 0 0118 14.158V11a6.002 6.002 0 00-4-5.659V5a2 2 0 10-4 0v.341C7.67 6.165 6 8.388 6 11v3.159c0 .538-.214 1.055-.595 1.436L4 17h5m6 0v1a3 3 0 11-6 0v-1m6 0H9' },
    { label: 'Analytics', href: '/analytics', icon: 'M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z' },
    { label: 'Audit Logs', href: '/audit', icon: 'M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-3 7h3m-3 4h3m-6-4h.01M9 16h.01' },
    { label: 'Settings', href: '/settings', icon: 'M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 11-6 0 3 3 0 016 0z', adminOnly: true },
    { label: 'Demo Engine', href: '/demo', icon: 'M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z M21 12a9 9 0 11-18 0 9 9 0 0118 0z', adminOnly: true },
  ];

  const visibleNav = isAdmin ? navItems : navItems.filter((item) => !item.adminOnly);

  const isActive = (href: string) => {
    if (href === '/dashboard') return pathname === '/dashboard';
    return pathname === href || pathname?.startsWith(href + '/');
  };

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-950 text-gray-900 dark:text-gray-100 flex flex-col font-sans">
      {/* Top Header */}
      <header className="sticky top-0 z-30 bg-white/90 dark:bg-gray-900/90 backdrop-blur border-b border-gray-200 dark:border-gray-800 px-4 sm:px-6 py-3.5 flex items-center justify-between gap-3">
        <div className="flex items-center space-x-4 min-w-0">
          <Link href="/dashboard" className="flex items-center space-x-2.5 shrink-0">
            <div className="w-8 h-8 rounded-lg bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center text-white font-black tracking-wider text-lg shadow-sm">
              M
            </div>
            <span className="font-bold text-xl tracking-tight text-gray-900 dark:text-white">Mend</span>
            <span className="text-xs px-2 py-0.5 rounded-md font-semibold bg-blue-50 text-blue-700 dark:bg-blue-950/60 dark:text-blue-400 border border-blue-200 dark:border-blue-800">
              Console
            </span>
          </Link>

          {user?.memberships && user.memberships.length > 1 ? (
            <div className="flex items-center space-x-2 bg-gray-100 dark:bg-gray-800 px-3 py-1.5 rounded-lg border border-gray-200 dark:border-gray-700">
              <span className="text-xs font-semibold text-gray-500 dark:text-gray-400 hidden sm:inline">Tenant:</span>
              <select
                value={currentMerchantId || ''}
                onChange={(e) => selectMerchant(e.target.value)}
                className="bg-transparent text-xs sm:text-sm font-semibold text-gray-900 dark:text-white focus:outline-none cursor-pointer max-w-[180px]"
              >
                {user.memberships.map((m) => (
                  <option key={m.merchantId} value={m.merchantId} className="bg-white dark:bg-gray-900 text-gray-900 dark:text-white">
                    {m.merchantName} ({m.roleName})
                  </option>
                ))}
              </select>
            </div>
          ) : (
            <div className="flex items-center space-x-2 bg-gray-100 dark:bg-gray-800/80 px-3 py-1.5 rounded-lg border border-gray-200 dark:border-gray-700">
              <span className="w-2 h-2 rounded-full bg-emerald-500" />
              <span className="text-xs font-semibold text-gray-700 dark:text-gray-300 truncate max-w-[180px]">
                {currentMerchantName || 'Merchant Console'}
              </span>
            </div>
          )}
        </div>

        <div className="flex items-center space-x-3 sm:space-x-4 shrink-0">
          <div className="hidden sm:flex flex-col text-right">
            <span className="text-xs font-semibold text-gray-900 dark:text-white">{user?.displayName || user?.email}</span>
            <span className="text-[10px] text-gray-500 dark:text-gray-400">{currentRole || 'User'}</span>
          </div>
          <button
            onClick={logout}
            className="px-3 py-1.5 border border-gray-200 dark:border-gray-700 hover:bg-rose-50 hover:text-rose-600 hover:border-rose-200 dark:hover:bg-rose-950/40 dark:hover:text-rose-400 rounded-lg text-xs font-semibold transition-colors"
          >
            Sign Out
          </button>
        </div>
      </header>

      {/* Main Navigation Bar */}
      <nav className="bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800 px-4 sm:px-6" aria-label="Primary">
        <div className="flex space-x-1 overflow-x-auto no-scrollbar">
          {visibleNav.map((item) => {
            const active = isActive(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                aria-current={active ? 'page' : undefined}
                className={`flex items-center space-x-2 px-4 py-3 border-b-2 text-sm font-semibold whitespace-nowrap transition-colors ${
                  active
                    ? 'border-blue-600 text-blue-600 dark:text-blue-400 dark:border-blue-400'
                    : 'border-transparent text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white hover:border-gray-300 dark:hover:border-gray-700'
                }`}
              >
                <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d={item.icon} />
                </svg>
                <span>{item.label}</span>
                {item.label === 'Recovery Actions' && pendingReviews > 0 && (
                  <span className="ml-1 inline-flex items-center px-1.5 py-0.5 rounded-full bg-amber-100 text-amber-800 dark:bg-amber-950 dark:text-amber-300 text-[10px] font-bold">
                    {pendingReviews}
                  </span>
                )}
              </Link>
            );
          })}
        </div>
      </nav>

      {/* Surface Content Container */}
      <main className="flex-1 w-full max-w-7xl mx-auto p-4 sm:p-6 md:p-8">{children}</main>

      {/* Console Footer */}
      <footer className="border-t border-gray-200 dark:border-gray-800 bg-white dark:bg-gray-900 py-4 px-6 text-center text-xs text-gray-500 dark:text-gray-400 flex flex-col sm:flex-row items-center justify-between gap-2">
        <div>Mend AI Payment Recovery Console &bull; Phase 19 Merchant Product</div>
        <div className="font-mono truncate max-w-full">Merchant Context: {currentMerchantId || 'N/A'}</div>
      </footer>
    </div>
  );
};
