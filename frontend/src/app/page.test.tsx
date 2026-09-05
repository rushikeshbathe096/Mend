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
    
    const heading = screen.getByRole('heading', { name: /Recover failed payments intelligently/i });
    expect(heading).toBeInTheDocument();
    
    const descriptor = screen.getByText(/Mend turns payment failure into a risk-aware recovery workflow/i);
    expect(descriptor).toBeInTheDocument();
    expect(screen.getByText('AI does not control money.')).toBeInTheDocument();
    expect(screen.getByText('Launch interactive demo →')).toBeInTheDocument();
  });
});
