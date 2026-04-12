import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ForgotPasswordPage from '../ForgotPasswordPage';

// Prevent real API calls
vi.mock('../../api/auth', () => ({
  forgotPassword: vi.fn().mockResolvedValue(undefined),
}));

function renderForgotPassword() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <ForgotPasswordPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ForgotPasswordPage', () => {
  it('renders the email input and submit button', () => {
    renderForgotPassword();
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /send reset link/i })).toBeInTheDocument();
  });

  it('shows validation error when submitted with empty email', async () => {
    renderForgotPassword();
    await userEvent.click(screen.getByRole('button', { name: /send reset link/i }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('shows error for an invalid email format', async () => {
    renderForgotPassword();
    await userEvent.type(screen.getByLabelText(/email/i), 'not-an-email');
    await userEvent.click(screen.getByRole('button', { name: /send reset link/i }));
    expect(await screen.findByText(/invalid email/i)).toBeInTheDocument();
  });

  it('shows "check your email" confirmation after successful submission', async () => {
    renderForgotPassword();
    await userEvent.type(screen.getByLabelText(/email/i), 'user@example.com');
    await userEvent.click(screen.getByRole('button', { name: /send reset link/i }));
    expect(await screen.findByText(/check your email/i)).toBeInTheDocument();
  });

  it('renders a "Back to sign in" link to /login', () => {
    renderForgotPassword();
    expect(screen.getByRole('link', { name: /back to sign in/i })).toHaveAttribute(
      'href',
      '/login',
    );
  });
});
