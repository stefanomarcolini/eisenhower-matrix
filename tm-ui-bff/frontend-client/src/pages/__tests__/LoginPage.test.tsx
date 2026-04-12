import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import LoginPage from '../LoginPage';

// Prevent real API calls in unit tests
vi.mock('../../api/auth', () => ({
  login: vi.fn(),
}));

function renderLogin() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <LoginPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('LoginPage', () => {
  it('renders email and password inputs', () => {
    renderLogin();
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument();
  });

  it('shows validation errors when submitted empty', async () => {
    renderLogin();
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    const alerts = await screen.findAllByRole('alert');
    expect(alerts.length).toBeGreaterThanOrEqual(2);
  });

  it('shows an error for an invalid email format', async () => {
    renderLogin();
    await userEvent.type(screen.getByLabelText(/email/i), 'not-an-email');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));
    expect(await screen.findByText(/invalid email/i)).toBeInTheDocument();
  });

  it('renders Google OAuth2 link with correct href', () => {
    renderLogin();
    expect(
      screen.getByText(/google/i).closest('a'),
    ).toHaveAttribute('href', '/oauth2/authorization/google');
  });

  it('renders Microsoft OAuth2 link with correct href', () => {
    renderLogin();
    expect(
      screen.getByText(/microsoft/i).closest('a'),
    ).toHaveAttribute('href', '/oauth2/authorization/microsoft');
  });

  it('shows a localhost mock OAuth hint for local development', () => {
    renderLogin();
    expect(screen.getByText(/mock oauth2 provider/i)).toBeInTheDocument();
  });

  it('renders a link to the register page', () => {
    renderLogin();
    expect(screen.getByRole('link', { name: /register/i })).toHaveAttribute('href', '/register');
  });

  it('renders a link to the forgot-password page', () => {
    renderLogin();
    expect(screen.getByRole('link', { name: /forgot password/i })).toHaveAttribute(
      'href',
      '/forgot-password',
    );
  });
});
