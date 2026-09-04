'use client';

import React, { createContext, useContext, useEffect, useState } from 'react';
import { User, Membership, LoginResponse, BootstrapResponse } from '@/types';

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

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [token, setToken] = useState<string | null>(null);
  const [currentMerchantId, setCurrentMerchantId] = useState<string | null>(null);
  const [currentMerchantName, setCurrentMerchantName] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);

  useEffect(() => {
    try {
      const storedToken = localStorage.getItem('mend_token');
      const storedUserRaw = localStorage.getItem('mend_user');
      const storedMerchantId = localStorage.getItem('mend_merchant_id');

      if (storedToken && storedUserRaw) {
        const parsedUser: User = JSON.parse(storedUserRaw);
        setToken(storedToken);
        setUser(parsedUser);

        if (storedMerchantId) {
          setCurrentMerchantId(storedMerchantId);
          const found = parsedUser.memberships?.find(m => m.merchantId === storedMerchantId);
          if (found) {
            setCurrentMerchantName(found.merchantName);
          }
        } else if (parsedUser.memberships && parsedUser.memberships.length > 0) {
          const first = parsedUser.memberships[0];
          setCurrentMerchantId(first.merchantId);
          setCurrentMerchantName(first.merchantName);
          localStorage.setItem('mend_merchant_id', first.merchantId);
        }
      }
    } catch (e) {
      console.error('Failed to load stored auth state:', e);
    } finally {
      setIsLoading(false);
    }
  }, []);

  const login = (data: LoginResponse) => {
    setToken(data.token);
    setUser(data.user);
    localStorage.setItem('mend_token', data.token);
    localStorage.setItem('mend_user', JSON.stringify(data.user));

    if (data.user.memberships && data.user.memberships.length > 0) {
      const first = data.user.memberships[0];
      setCurrentMerchantId(first.merchantId);
      setCurrentMerchantName(first.merchantName);
      localStorage.setItem('mend_merchant_id', first.merchantId);
    }
  };

  const bootstrapLogin = (token: string, user: User, merchantId: string) => {
    setToken(token);
    setUser(user);
    setCurrentMerchantId(merchantId);
    if (user.memberships && user.memberships.length > 0) {
      const found = user.memberships.find(m => m.merchantId === merchantId);
      setCurrentMerchantName(found ? found.merchantName : 'Merchant');
    }
    localStorage.setItem('mend_token', token);
    localStorage.setItem('mend_user', JSON.stringify(user));
    localStorage.setItem('mend_merchant_id', merchantId);
  };

  const logout = () => {
    setToken(null);
    setUser(null);
    setCurrentMerchantId(null);
    setCurrentMerchantName(null);
    localStorage.removeItem('mend_token');
    localStorage.removeItem('mend_user');
    localStorage.removeItem('mend_merchant_id');
  };

  const selectMerchant = (merchantId: string) => {
    if (!user) return;
    const found = user.memberships?.find(m => m.merchantId === merchantId);
    if (found) {
      setCurrentMerchantId(merchantId);
      setCurrentMerchantName(found.merchantName);
      localStorage.setItem('mend_merchant_id', merchantId);
    }
  };

  return (
    <AuthContext.Provider
      value={{
        user,
        token,
        currentMerchantId,
        currentMerchantName,
        isLoading,
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
