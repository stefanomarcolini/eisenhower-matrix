import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import MfaVerifyPage from '../MfaVerifyPage';
import type { SessionData } from '../../hooks/useSession';

// Prevent real API calls
vi.mock('../../api/auth', () => ({
  verifyMfa: vi.fn(),
}));

const MFA_PENDING_SESSION: SessionData = {
  isAuthenticated: false,
  userId:          null,
  email:           'alice@example.com',
  tenantId:        't1',
  role:            null,
  mfaPending:      true,
  passwordWarning: false,
};

const AUTHENTICATED_SESSION: SessionData = {
  isAuthenticated: true,
  userId:          'u1',
  email:           'alice@example.com',
  tenantId:        't1',
  role:            'STANDARD',
  mfaPending:      false,
  passwordWarning: false,
};

const UNAUTHENTICATED_SESSION: SessionData = {
  isAuthenticated: false,
  userId:          null,
  email:           null,
  tenantId:        null,
  role:            null,
  mfaPending:      false,
  passwordWarning: false,
};

function renderMfaVerify(session: SessionData) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  qc.setQueryData(['session'], session);
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/mfa']}>
        <Routes>
          <Route path="/mfa"       element={<MfaVerifyPage />} />
          <Route path="/login"     element={<div>Login Page</div>} />
          <Route path="/dashboard" element={<div>Dashboard</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );

  return qc;
}

describe('MfaVerifyPage', () => {
  it('redirects to /login when not mfaPending and not authenticated', () => {
    renderMfaVerify(UNAUTHENTICATED_SESSION);
    expect(screen.getByText('Login Page')).toBeInTheDocument();
  });

  it('keeps the verify page visible when authenticated and not mfaPending', () => {
    renderMfaVerify(AUTHENTICATED_SESSION);
    expect(screen.getByLabelText(/verification code/i)).toBeInTheDocument();
  });

  it('renders the verification code input when mfaPending is true', () => {
    renderMfaVerify(MFA_PENDING_SESSION);
    expect(screen.getByLabelText(/verification code/i)).toBeInTheDocument();
  });

  it('shows a validation error when submitted with an empty code', async () => {
    renderMfaVerify(MFA_PENDING_SESSION);
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
  });

  it('shows a validation error when code is not 6 digits', async () => {
    renderMfaVerify(MFA_PENDING_SESSION);
    await userEvent.type(screen.getByLabelText(/verification code/i), '123');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(await screen.findByText('Must be a 6-digit code')).toBeInTheDocument();
  });

  it('shows "Invalid code" error message when the mutation fails', async () => {
    const { verifyMfa } = await import('../../api/auth');
    vi.mocked(verifyMfa).mockRejectedValueOnce(new Error('Invalid code'));
    renderMfaVerify(MFA_PENDING_SESSION);
    await userEvent.type(screen.getByLabelText(/verification code/i), '123456');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));
    expect(await screen.findByText(/invalid code/i)).toBeInTheDocument();
  });

  it('navigates to /dashboard after a successful verify even when session refetch fails', async () => {
    const { verifyMfa } = await import('../../api/auth');
    vi.mocked(verifyMfa).mockResolvedValueOnce(undefined);

    const qc = renderMfaVerify(MFA_PENDING_SESSION);
    vi.spyOn(qc, 'refetchQueries').mockRejectedValueOnce(new Error('session refetch failed'));

    await userEvent.type(screen.getByLabelText(/verification code/i), '123456');
    await userEvent.click(screen.getByRole('button', { name: /verify/i }));

    expect(await screen.findByText('Dashboard')).toBeInTheDocument();
  });
});
