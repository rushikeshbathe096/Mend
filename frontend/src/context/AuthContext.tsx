'use client';

import React, { createContext, useContext, useSyncExternalStore } from 'react';
import { User, LoginResponse } from '@/types';

interface AuthContextType {
  user: User | null;
  token: string | null;
  currentMerchantId: string | null;
  currentMerchantName: string | null;
  isLoading: boolean;
  login: (data: LoginResponse) => void;
  bootstrapLogin: (token: string, user: User, merchantId: string) => void;
  logout: () => void;
  selectMerchant: (merchantId: string) => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

const TOKEN_KEY = 'mend_token';
const USER_KEY = 'mend_user';
const MERCHANT_KEY = 'mend_merchant_id';

interface StoredAuth {
  user: User | null;
  token: string | null;
  merchantId: string | null;
  merchantName: string | null;
}

const SIGNED_OUT: StoredAuth = { user: null, token: null, merchantId: null, merchantName: null };

/**
 * The session lives in localStorage; components read it through useSyncExternalStore (no setState in an effect).
 * The server snapshot is null ("not loaded yet"), so the first client render matches the server render and isLoading
 * becomes false once the browser snapshot is read.
 */
const listeners = new Set<() => void>();
let cachedRaw: string | null = null;
let cachedAuth: StoredAuth = SIGNED_OUT;

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  window.addEventListener('storage', listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener('storage', listener);
  };
}

function notify() {
  listeners.forEach((listener) => listener());
}

function readStoredAuth(): StoredAuth {
  const token = localStorage.getItem(TOKEN_KEY);
  const userRaw = localStorage.getItem(USER_KEY);
  const storedMerchantId = localStorage.getItem(MERCHANT_KEY);
  const raw = JSON.stringify([token, userRaw, storedMerchantId]);
  if (raw === cachedRaw) {
    return cachedAuth;   // same snapshot object while nothing changed (useSyncExternalStore requirement)
  }
  cachedRaw = raw;
  cachedAuth = SIGNED_OUT;
  if (token && userRaw) {
    try {
      const user: User = JSON.parse(userRaw);
      // A stored merchant the user is no longer a member of falls back to the first membership.
      const membership = user.memberships?.find((m) => m.merchantId === storedMerchantId) ?? user.memberships?.[0] ?? null;
      cachedAuth = {
        user,
        token,
        merchantId: membership?.merchantId ?? null,
        merchantName: membership?.merchantName ?? null,
      };
    } catch (e) {
      console.error('Failed to load stored auth state:', e);
    }
  }
  return cachedAuth;
}

function writeSession(token: string, user: User, merchantId: string | null) {
  localStorage.setItem(TOKEN_KEY, token);
  localStorage.setItem(USER_KEY, JSON.stringify(user));
  if (merchantId) {
    localStorage.setItem(MERCHANT_KEY, merchantId);
  } else {
    localStorage.removeItem(MERCHANT_KEY);
  }
  notify();
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const stored = useSyncExternalStore(subscribe, readStoredAuth, () => null);
  const auth = stored ?? SIGNED_OUT;

  const login = (data: LoginResponse) => {
    writeSession(data.token, data.user, data.user.memberships?.[0]?.merchantId ?? null);
  };

  const bootstrapLogin = (token: string, user: User, merchantId: string) => {
    const known = user.memberships?.some((m) => m.merchantId === merchantId);
    writeSession(token, user, known || !user.memberships?.length ? merchantId : user.memberships[0].merchantId);
  };

  const logout = () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    localStorage.removeItem(MERCHANT_KEY);
    notify();
  };

  const selectMerchant = (merchantId: string) => {
    if (!auth.user?.memberships?.some((m) => m.merchantId === merchantId)) return;
    localStorage.setItem(MERCHANT_KEY, merchantId);
    notify();
  };

  return (
    <AuthContext.Provider
      value={{
        user: auth.user,
        token: auth.token,
        currentMerchantId: auth.merchantId,
        currentMerchantName: auth.merchantName,
        isLoading: stored === null,
        login,
        bootstrapLogin,
        logout,
        selectMerchant,
      }}
    >
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}
