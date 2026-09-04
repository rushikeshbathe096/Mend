import { render, screen } from '@testing-library/react';
import Home from './page';

jest.mock('next/navigation', () => ({
  useRouter: () => ({
    push: jest.fn(),
  }),
}));

jest.mock('@/context/AuthContext', () => ({
  useAuth: () => ({
    token: null,
    isLoading: false,
  }),
}));

describe('Home Page', () => {
  it('renders the application title and descriptor', () => {
    render(<Home />);
    
    const heading = screen.getByRole('heading', { name: /Mend Payment Recovery Operations/i });
    expect(heading).toBeInTheDocument();
    
    const descriptor = screen.getByText(/Failed payments are detected, classified, evaluated/i);
    expect(descriptor).toBeInTheDocument();
  });
});
