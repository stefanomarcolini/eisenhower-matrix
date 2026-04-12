import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import SettingsPage from '../SettingsPage';
import type { UserProfile } from '../../api/profile';
import * as profileApi from '../../api/profile';

vi.mock('qrcode', () => ({
  default: {
    toDataURL: vi.fn().mockResolvedValue('data:image/png;base64,qr-code'),
  },
}));

vi.mock('../../api/profile', () => ({
  getProfile:           vi.fn(),
  updateProfile:        vi.fn(),
  changePassword:       vi.fn(),
  initMfaEnrollment:    vi.fn(),
  confirmMfaEnrollment: vi.fn(),
  disableMfa:           vi.fn(),
}));

const BASE_PROFILE: UserProfile = {
  id: 'u1', email: 'alice@example.com', displayName: 'Alice',
  role: 'STANDARD', authProvider: 'LOCAL',
  isMfaEnabled: false, theme: 'LIGHT',
  createdAt: '2025-01-01T00:00:00Z', updatedAt: '2025-01-01T00:00:00Z',
};

function renderSettings(profile: UserProfile) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  qc.setQueryData(['profile'], profile);
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SettingsPage', () => {
  it('renders the profile section with a display name input', () => {
    renderSettings(BASE_PROFILE);
    expect(screen.getByTestId('profile-section')).toBeInTheDocument();
    expect(screen.getByLabelText(/display name/i)).toHaveValue('Alice');
  });

  it('renders the theme selector', () => {
    renderSettings(BASE_PROFILE);
    expect(screen.getByLabelText(/theme/i)).toHaveValue('LIGHT');
  });

  it('shows the change-password section for LOCAL users', () => {
    renderSettings(BASE_PROFILE);
    expect(screen.getByTestId('change-password-section')).toBeInTheDocument();
  });

  it('hides the change-password section for OAuth2 users', () => {
    renderSettings({ ...BASE_PROFILE, authProvider: 'GOOGLE' });
    expect(screen.queryByTestId('change-password-section')).not.toBeInTheDocument();
  });

  it('shows the MFA section', () => {
    renderSettings(BASE_PROFILE);
    expect(screen.getByTestId('mfa-section')).toBeInTheDocument();
  });

  it('shows "Enable MFA" button when MFA is disabled', () => {
    renderSettings({ ...BASE_PROFILE, isMfaEnabled: false });
    expect(screen.getByRole('button', { name: /enable mfa/i })).toBeInTheDocument();
  });

  it('shows MFA enabled status when MFA is active', () => {
    renderSettings({ ...BASE_PROFILE, isMfaEnabled: true });
    expect(screen.getByText(/mfa is enabled/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /disable mfa/i })).toBeInTheDocument();
  });

  it('opens the MFA enrollment dialog when enabling MFA', async () => {
    vi.mocked(profileApi.initMfaEnrollment).mockResolvedValue({
      secret: 'JBSWY3DPEHPK3PXP',
      otpauthUri: 'otpauth://totp/Task%20Manager:test?secret=JBSWY3DPEHPK3PXP&issuer=Task%20Manager',
    });

    const user = userEvent.setup();
    renderSettings({ ...BASE_PROFILE, isMfaEnabled: false });

    await user.click(screen.getByRole('button', { name: /enable mfa/i }));

    expect(await screen.findByTestId('mfa-enroll-dialog')).toBeInTheDocument();
    expect(screen.getByTestId('mfa-qr-code')).toBeInTheDocument();
  });
});
