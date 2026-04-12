import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import ResetPasswordPage from '../ResetPasswordPage';

// Prevent real API calls
vi.mock('../../api/auth', () => ({
  resetPassword: vi.fn(),
}));

// Stub the heavy zxcvbn dependency
vi.mock('../../components/PasswordStrengthIndicator', () => ({
  PasswordStrengthIndicator: () => null,
}));

function renderResetPassword(initialPath = '/reset-password?token=abc123') {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialPath]}>
        <ResetPasswordPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('ResetPasswordPage', () => {
  it('shows "Invalid or missing reset token" when no token query param is present', () => {
    renderResetPassword('/reset-password');
    expect(screen.getByText(/invalid or missing reset token/i)).toBeInTheDocument();
  });

  it('renders a link to /forgot-password when no token is present', () => {
    renderResetPassword('/reset-password');
    expect(screen.getByRole('link', { name: /request a new link/i })).toHaveAttribute(
      'href',
      '/forgot-password',
    );
  });

  it('renders new password and confirm password inputs when token is present', () => {
    renderResetPassword();
    expect(screen.getByLabelText(/^new password/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/confirm password/i)).toBeInTheDocument();
  });

  it('shows required validation errors when submitted empty', async () => {
    renderResetPassword();
    await userEvent.click(screen.getByRole('button', { name: /set new password/i }));
    const alerts = await screen.findAllByRole('alert');
    expect(alerts.length).toBeGreaterThanOrEqual(1);
  });

  it('shows error when passwords do not match', async () => {
    renderResetPassword();
    await userEvent.type(screen.getByLabelText(/^new password/i), 'Str0ng!Pass#1');
    await userEvent.type(screen.getByLabelText(/confirm password/i), 'Different!Pass2');
    await userEvent.click(screen.getByRole('button', { name: /set new password/i }));
    expect(await screen.findByText(/do not match/i)).toBeInTheDocument();
  });

  it('shows "invalid or expired" error when the mutation fails', async () => {
    const { resetPassword } = await import('../../api/auth');
    vi.mocked(resetPassword).mockRejectedValueOnce(new Error('Bad request'));
    renderResetPassword();
    await userEvent.type(screen.getByLabelText(/^new password/i), 'Str0ng!Pass#1');
    await userEvent.type(screen.getByLabelText(/confirm password/i), 'Str0ng!Pass#1');
    await userEvent.click(screen.getByRole('button', { name: /set new password/i }));
    expect(await screen.findByText(/invalid or expired/i)).toBeInTheDocument();
  });
});
