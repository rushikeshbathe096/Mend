'use client';

import React, { useState } from 'react';
import { useRouter } from 'next/navigation';
import { useAuth } from '@/context/AuthContext';
import { api, ApiError } from '@/lib/api';
import { LoginResponse, BootstrapResponse } from '@/types';

export default function LoginPage() {
  const router = useRouter();
  const { login, bootstrapLogin } = useAuth();

  const [mode, setMode] = useState<'login' | 'bootstrap'>('login');
  
  // Login Form State
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  // Bootstrap Form State
  const [merchantName, setMerchantName] = useState('Acme Enterprise');
  const [bootstrapEmail, setBootstrapEmail] = useState('admin@acme.com');
  const [bootstrapPassword, setBootstrapPassword] = useState('AdminPass123!');
  const [userDisplayName, setUserDisplayName] = useState('Acme Admin');

  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleLoginSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const data = await api.post<LoginResponse>('/auth/login', { email, password });
      login(data);
      router.push('/dashboard');
    } catch (err: any) {
      if (err instanceof ApiError) {
        setError(err.message || 'Invalid credentials or login failure');
      } else {
        setError('Failed to connect to Mend API server');
      }
    } finally {
      setLoading(false);
    }
  };

  const handleBootstrapSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setLoading(true);

    try {
      const data = await api.post<BootstrapResponse>('/auth/bootstrap', {
        merchantName,
        adminEmail: bootstrapEmail,
        adminPassword: bootstrapPassword,
        adminDisplayName: userDisplayName,
      });

      // Automatically login after successful bootstrap
      const loginData = await api.post<LoginResponse>('/auth/login', {
        email: bootstrapEmail,
        password: bootstrapPassword,
      });

      login(loginData);
      router.push('/dashboard');
    } catch (err: any) {
      if (err instanceof ApiError) {
        setError(err.message || 'Bootstrap failed');
      } else {
        setError('Failed to bootstrap merchant context');
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-950 p-4 font-sans text-gray-900 dark:text-gray-100">
      <div className="w-full max-w-md bg-white dark:bg-gray-900 rounded-2xl shadow-xl border border-gray-200 dark:border-gray-800 overflow-hidden">
        {/* Header */}
        <div className="p-8 pb-6 text-center border-b border-gray-100 dark:border-gray-800 bg-gradient-to-b from-blue-50/50 dark:from-blue-950/20 to-transparent">
          <div className="w-12 h-12 rounded-xl bg-gradient-to-tr from-blue-600 to-indigo-600 flex items-center justify-center text-white font-black text-2xl shadow-md mx-auto mb-3">
            M
          </div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-white">Mend Merchant Console</h1>
          <p className="text-sm text-gray-500 dark:text-gray-400 mt-1">Autonomous AI Payment Recovery</p>

          {/* Mode Switcher */}
          <div className="flex bg-gray-100 dark:bg-gray-800 p-1 rounded-xl mt-6 text-xs font-semibold">
            <button
              onClick={() => { setMode('login'); setError(null); }}
              className={`flex-1 py-2 rounded-lg transition-all ${
                mode === 'login'
                  ? 'bg-white dark:bg-gray-900 text-blue-600 dark:text-blue-400 shadow-sm'
                  : 'text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white'
              }`}
            >
              Sign In
            </button>
            <button
              onClick={() => { setMode('bootstrap'); setError(null); }}
              className={`flex-1 py-2 rounded-lg transition-all ${
                mode === 'bootstrap'
                  ? 'bg-white dark:bg-gray-900 text-blue-600 dark:text-blue-400 shadow-sm'
                  : 'text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-white'
              }`}
            >
              Bootstrap Merchant
            </button>
          </div>
        </div>

        {/* Content Body */}
        <div className="p-8">
          {error && (
            <div className="mb-6 p-4 rounded-xl bg-rose-50 dark:bg-rose-950/40 border border-rose-200 dark:border-rose-800 text-rose-800 dark:text-rose-300 text-sm font-medium flex items-center space-x-2">
              <svg className="w-5 h-5 text-rose-500 flex-shrink-0" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M12 8v4m0 4h.01M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
              </svg>
              <span>{error}</span>
            </div>
          )}

          {mode === 'login' ? (
            <form onSubmit={handleLoginSubmit} className="space-y-5">
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5 uppercase tracking-wider">
                  Email Address
                </label>
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  placeholder="merchant@example.com"
                  className="w-full px-4 py-2.5 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-600 focus:border-transparent text-sm outline-none transition-all"
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1.5 uppercase tracking-wider">
                  Password
                </label>
                <input
                  type="password"
                  required
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••••••"
                  className="w-full px-4 py-2.5 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white focus:ring-2 focus:ring-blue-600 focus:border-transparent text-sm outline-none transition-all"
                />
              </div>

              <button
                type="submit"
                disabled={loading}
                className="w-full py-3 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white font-semibold rounded-xl text-sm shadow-md disabled:opacity-50 transition-all flex items-center justify-center space-x-2"
              >
                {loading ? (
                  <>
                    <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    <span>Signing In...</span>
                  </>
                ) : (
                  <span>Authenticate Session</span>
                )}
              </button>
            </form>
          ) : (
            <form onSubmit={handleBootstrapSubmit} className="space-y-4">
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1 uppercase tracking-wider">
                  Merchant Name
                </label>
                <input
                  type="text"
                  required
                  value={merchantName}
                  onChange={(e) => setMerchantName(e.target.value)}
                  className="w-full px-3.5 py-2 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white text-sm outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1 uppercase tracking-wider">
                  Admin Display Name
                </label>
                <input
                  type="text"
                  required
                  value={userDisplayName}
                  onChange={(e) => setUserDisplayName(e.target.value)}
                  className="w-full px-3.5 py-2 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white text-sm outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1 uppercase tracking-wider">
                  Admin Email
                </label>
                <input
                  type="email"
                  required
                  value={bootstrapEmail}
                  onChange={(e) => setBootstrapEmail(e.target.value)}
                  className="w-full px-3.5 py-2 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white text-sm outline-none"
                />
              </div>
              <div>
                <label className="block text-xs font-semibold text-gray-700 dark:text-gray-300 mb-1 uppercase tracking-wider">
                  Password
                </label>
                <input
                  type="password"
                  required
                  value={bootstrapPassword}
                  onChange={(e) => setBootstrapPassword(e.target.value)}
                  className="w-full px-3.5 py-2 rounded-xl border border-gray-300 dark:border-gray-700 bg-white dark:bg-gray-800 text-gray-900 dark:text-white text-sm outline-none"
                />
              </div>

              <button
                type="submit"
                disabled={loading}
                className="w-full py-3 mt-2 bg-gradient-to-r from-blue-600 to-indigo-600 hover:from-blue-700 hover:to-indigo-700 text-white font-semibold rounded-xl text-sm shadow-md disabled:opacity-50 transition-all flex items-center justify-center space-x-2"
              >
                {loading ? (
                  <>
                    <div className="w-4 h-4 border-2 border-white border-t-transparent rounded-full animate-spin" />
                    <span>Bootstrapping Merchant...</span>
                  </>
                ) : (
                  <span>Bootstrap & Sign In</span>
                )}
              </button>
            </form>
          )}
        </div>
      </div>
    </div>
  );
}
