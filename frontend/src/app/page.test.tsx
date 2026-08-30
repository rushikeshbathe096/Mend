import { render, screen } from '@testing-library/react'
import Home from './page'

describe('Home Page', () => {
  it('renders the application title and descriptor', () => {
    render(<Home />)
    
    const heading = screen.getByRole('heading', { name: /Mend/i })
    expect(heading).toBeInTheDocument()
    
    const descriptor = screen.getByText(/AI-powered payment recovery platform/i)
    expect(descriptor).toBeInTheDocument()
  })
})
