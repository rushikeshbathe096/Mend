import { render, screen, waitFor, within } from '@testing-library/react';
import WebhooksPage from './page';
import { api } from '@/lib/api';

jest.mock('@/lib/api', () => ({
  ...jest.requireActual('@/lib/api'),
  api: {
    get: jest.fn(),
    post: jest.fn(),
    put: jest.fn(),
    delete: jest.fn(),
  },
}));
jest.mock('@/components/layout/ConsoleLayout', () => ({
  ConsoleLayout: ({ children }: { children: React.ReactNode }) => <div>{children}</div>,
}));
jest.mock('next/navigation', () => ({
  useRouter: () => ({ push: jest.fn() }),
  usePathname: () => '/webhooks',
}));
jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    token: 'jwt-token',
    currentMerchantId: 'm-456',
    user: { memberships: [{ merchantId: 'm-456', roleName: 'MERCHANT_ADMIN' }] },
    isLoading: false,
  }),
}));

describe('WebhooksPage status filter', () => {
  it('offers only real WebhookEventStatus values', async () => {
    (api.get as jest.Mock).mockResolvedValue({ content: [], totalPages: 1, totalElements: 0 });

    render(<WebhooksPage />);
    await waitFor(() => expect(api.get).toHaveBeenCalled());

    const select = screen.getByDisplayValue('ALL') as HTMLSelectElement;
    const options = within(select).getAllByRole('option').map((o) => (o as HTMLOptionElement).value);
    expect(options).toEqual(['ALL', 'RECEIVED', 'VERIFIED', 'PROCESSED', 'FAILED']);
  });
});
