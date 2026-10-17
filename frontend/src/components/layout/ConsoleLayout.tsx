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

  const showsReviews = Boolean(token && currentMerchantId && isAdmin);

  React.useEffect(() => {
    if (!showsReviews) return;
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
  }, [showsReviews, token, currentMerchantId, pathname]);
  const pendingReviewCount = showsReviews ? pendingReviews : 0;

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
    <div className="app-shell min-h-screen text-[var(--foreground)]">
      <aside className="app-sidebar fixed inset-y-0 left-0 z-40 hidden w-64 flex-col border-r border-white/10 lg:flex">
        <div className="flex h-20 items-center gap-3 border-b border-white/10 px-6">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-blue-500 text-lg font-black text-white">M</div>
          <div><div className="text-lg font-bold tracking-tight text-white">Mend</div><div className="text-[10px] font-semibold uppercase tracking-[0.16em] text-slate-400">Recovery console</div></div>
        </div>
        <div className="px-4 py-5">
          <div className="mb-3 px-3 text-[10px] font-bold uppercase tracking-[0.16em] text-slate-500">Workspace</div>
          <nav className="space-y-1" aria-label="Primary">
            {visibleNav.map((item) => {
              const active = isActive(item.href);
              return <Link key={item.href} href={item.href} aria-current={active ? 'page' : undefined} className={`group relative flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium ${active ? 'bg-white/10 text-white' : 'text-slate-400 hover:bg-white/5 hover:text-slate-100'}`}>
                {active && <span className="absolute left-0 h-6 w-0.5 rounded-r bg-blue-400" />}
                <svg className={`h-4 w-4 ${active ? 'text-blue-300' : 'text-slate-500 group-hover:text-slate-300'}`} fill="none" viewBox="0 0 24 24" stroke="currentColor"><path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.8} d={item.icon} /></svg>
                <span>{item.label}</span>
                {item.label === 'Recovery Actions' && pendingReviewCount > 0 && <span className="ml-auto rounded-full bg-amber-400/15 px-2 py-0.5 text-[10px] font-bold text-amber-300">{pendingReviewCount}</span>}
              </Link>;
            })}
          </nav>
        </div>
        <div className="mt-auto border-t border-white/10 p-4">
          <div className="mb-3 rounded-lg bg-white/5 p-3">
            <div className="mb-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-500">Merchant</div>
            {user?.memberships && user.memberships.length > 1 ? <select value={currentMerchantId || ''} onChange={(e) => selectMerchant(e.target.value)} className="w-full bg-transparent text-sm font-semibold text-white outline-none"><option value="" disabled>Select merchant</option>{user.memberships.map((m) => <option key={m.merchantId} value={m.merchantId} className="bg-slate-900">{m.merchantName}</option>)}</select> : <div className="truncate text-sm font-semibold text-slate-200">{currentMerchantName || 'Merchant Console'}</div>}
          </div>
          <div className="flex items-center gap-3 px-2">
            <div className="flex h-8 w-8 items-center justify-center rounded-full bg-blue-500/20 text-xs font-bold text-blue-200">{(user?.displayName || user?.email || 'U').slice(0, 1).toUpperCase()}</div>
            <div className="min-w-0 flex-1"><div className="truncate text-xs font-semibold text-slate-200">{user?.displayName || user?.email}</div><div className="text-[10px] text-slate-500">{currentRole || 'User'}</div></div>
            <button onClick={logout} aria-label="Sign out" title="Sign out" className="flex items-center gap-1 rounded-md px-2 py-1 text-xs font-semibold text-slate-400 hover:bg-white/10 hover:text-white transition">
              <svg className="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
              </svg>
              <span>Log out</span>
            </button>
          </div>
        </div>
      </aside>

      <div className="lg:pl-64">
        <header className="sticky top-0 z-30 border-b border-[var(--border)] bg-white/90 px-4 py-3 backdrop-blur sm:px-6 lg:px-8">
          <div className="flex items-center justify-between gap-4">
            <div><div className="text-xs font-semibold text-[var(--ink-muted)] lg:hidden">MEND / CONSOLE</div><div className="text-sm font-semibold text-[var(--navy)]">{currentMerchantName || 'Merchant workspace'}</div></div>
            <div className="flex items-center gap-3">
              <div className="hidden text-right sm:block">
                <div className="text-xs font-semibold text-[var(--navy)]">{user?.displayName || user?.email}</div>
                <div className="text-[10px] text-[var(--ink-muted)]">{currentRole || 'User'}</div>
              </div>
              <button
                onClick={logout}
                className="flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-bold text-slate-700 shadow-sm transition hover:border-slate-300 hover:bg-slate-50 hover:text-rose-600"
              >
                <svg className="w-3.5 h-3.5 text-slate-500 hover:text-rose-600" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
                </svg>
                <span>Log out</span>
              </button>
            </div>
          </div>
          <nav className="mt-3 flex gap-1 overflow-x-auto no-scrollbar lg:hidden" aria-label="Mobile primary">
            {visibleNav.map((item) => { const active = isActive(item.href); return <Link key={item.href} href={item.href} className={`whitespace-nowrap rounded-md px-3 py-1.5 text-xs font-semibold ${active ? 'bg-blue-50 text-blue-700' : 'text-[var(--ink-muted)] hover:bg-[var(--surface-muted)]'}`}>{item.label}</Link>; })}
          </nav>
        </header>
        <main className="mx-auto min-h-[calc(100vh-112px)] w-full max-w-[1440px] p-4 sm:p-6 lg:p-8">{children}</main>
        <footer className="border-t border-[var(--border)] bg-white px-6 py-4 text-xs text-[var(--ink-muted)]"><div className="mx-auto flex max-w-[1440px] items-center justify-between gap-3"><span>Mend recovery operations</span><span className="font-mono">{currentMerchantId || 'No merchant context'}</span></div></footer>
      </div>
    </div>
  );
};
