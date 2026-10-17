import { fireEvent, render, screen } from '@testing-library/react';
import { AuthProvider, useAuth } from './AuthContext';
import { LoginResponse, User } from '@/types';

const user: User = {
  id: 'u-1',
  email: 'admin@test.invalid',
  displayName: 'Admin',
  memberships: [
    { merchantId: 'm-1', merchantName: 'Merchant One', roleName: 'MERCHANT_ADMIN' },
    { merchantId: 'm-2', merchantName: 'Merchant Two', roleName: 'VIEWER' },
  ],
};

const loginResponse: LoginResponse = { token: 'jwt-2', expiresIn: 3600, user };

function Probe() {
  const auth = useAuth();
  return (
    <div>
      <button onClick={() => auth.login(loginResponse)}>login</button>
      <button onClick={() => auth.selectMerchant('m-2')}>select m-2</button>
      <button onClick={() => auth.selectMerchant('m-not-a-member')}>select other</button>
      <button onClick={() => auth.logout()}>logout</button>
      <span data-testid="token">{auth.token ?? 'none'}</span>
      <span data-testid="merchant">{auth.currentMerchantId ?? 'none'}</span>
      <span data-testid="merchant-name">{auth.currentMerchantName ?? 'none'}</span>
      <span data-testid="loading">{String(auth.isLoading)}</span>
    </div>
  );
}

function renderProvider() {
  return render(
    <AuthProvider>
      <Probe />
    </AuthProvider>,
  );
}

describe('AuthProvider (localStorage session through useSyncExternalStore)', () => {
  beforeEach(() => localStorage.clear());

  it('restores a stored session and is not loading', () => {
    localStorage.setItem('mend_token', 'jwt-1');
    localStorage.setItem('mend_user', JSON.stringify(user));
    localStorage.setItem('mend_merchant_id', 'm-2');

    renderProvider();

    expect(screen.getByTestId('token')).toHaveTextContent('jwt-1');
    expect(screen.getByTestId('merchant')).toHaveTextContent('m-2');
    expect(screen.getByTestId('merchant-name')).toHaveTextContent('Merchant Two');
    expect(screen.getByTestId('loading')).toHaveTextContent('false');
  });

  it('falls back to the first membership when the stored merchant is not one of the user’s', () => {
    localStorage.setItem('mend_token', 'jwt-1');
    localStorage.setItem('mend_user', JSON.stringify(user));
    localStorage.setItem('mend_merchant_id', 'm-unknown');

    renderProvider();

    expect(screen.getByTestId('merchant')).toHaveTextContent('m-1');
  });

  it('login, selectMerchant and logout update the session and storage', () => {
    renderProvider();
    expect(screen.getByTestId('token')).toHaveTextContent('none');

    fireEvent.click(screen.getByText('login'));
    expect(screen.getByTestId('token')).toHaveTextContent('jwt-2');
    expect(screen.getByTestId('merchant')).toHaveTextContent('m-1');
    expect(localStorage.getItem('mend_merchant_id')).toBe('m-1');

    fireEvent.click(screen.getByText('select m-2'));
    expect(screen.getByTestId('merchant')).toHaveTextContent('m-2');
    fireEvent.click(screen.getByText('select other'));
    expect(screen.getByTestId('merchant')).toHaveTextContent('m-2');

    fireEvent.click(screen.getByText('logout'));
    expect(screen.getByTestId('token')).toHaveTextContent('none');
    expect(localStorage.getItem('mend_token')).toBeNull();
  });

  it('a corrupt stored user is treated as signed out', () => {
    localStorage.setItem('mend_token', 'jwt-1');
    localStorage.setItem('mend_user', '{not json');
    const error = jest.spyOn(console, 'error').mockImplementation(() => {});

    renderProvider();

    expect(screen.getByTestId('token')).toHaveTextContent('none');
    error.mockRestore();
  });
});
