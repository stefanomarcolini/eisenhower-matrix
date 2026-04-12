import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AppLayout } from '../AppLayout';
import type { SessionData } from '../../hooks/useSession';
import type { UserProfile } from '../../api/profile';

vi.mock('../../api/auth', () => ({
  logout: vi.fn().mockResolvedValue(undefined),
}));

// Prevent real HTTP calls from session/profile queries
vi.mock('../../api/client', () => ({
  apiClient: {
    get:    vi.fn().mockResolvedValue({ data: {} }),
    post:   vi.fn().mockResolvedValue({ data: {} }),
    put:    vi.fn().mockResolvedValue({ data: {} }),
    patch:  vi.fn().mockResolvedValue({ data: {} }),
    delete: vi.fn().mockResolvedValue({ data: {} }),
  },
}));

const SESSION: SessionData = {
  isAuthenticated: true,
  userId:   'u1',
  email:    'alice@example.com',
  tenantId: 't1',
  role:     'STANDARD',
  mfaPending:      false,
  passwordWarning: false,
};

const PROFILE: UserProfile = {
  id:            'u1',
  email:         'alice@example.com',
  displayName:   'Alice Smith',
  role:          'STANDARD',
  authProvider:  'LOCAL',
  isMfaEnabled:  false,
  theme:         'LIGHT',
  createdAt:     '2025-01-01T00:00:00Z',
  updatedAt:     '2025-01-01T00:00:00Z',
};

function renderLayout(
  initialPath = '/dashboard',
  profile: UserProfile | null = PROFILE,
) {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  qc.setQueryData(['session'], SESSION);
  if (profile) qc.setQueryData(['profile'], profile);
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[initialPath]}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/dashboard" element={<div>Dashboard Content</div>} />
            <Route path="/settings"  element={<div>Settings Content</div>} />
          </Route>
          <Route path="/login" element={<div>Login Page</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return qc;
}

describe('AppLayout', () => {
  it('renders the navigation bar', () => {
    renderLayout();
    expect(screen.getByRole('navigation')).toBeInTheDocument();
  });

  it('renders a Dashboard nav link', () => {
    renderLayout();
    expect(screen.getByRole('link', { name: /dashboard/i })).toBeInTheDocument();
  });

  it('renders a Settings nav link', () => {
    renderLayout();
    expect(screen.getByRole('link', { name: /settings/i })).toBeInTheDocument();
  });

  it('renders the logout button', () => {
    renderLayout();
    expect(screen.getByRole('button', { name: /log out/i })).toBeInTheDocument();
  });

  it('shows user initials derived from the display name', () => {
    renderLayout();
    // 'Alice Smith' → 'AS'
    expect(screen.getByText('AS')).toBeInTheDocument();
  });

  it('renders child route content via Outlet', () => {
    renderLayout('/dashboard');
    expect(screen.getByText('Dashboard Content')).toBeInTheDocument();
  });

  it('calls the logout API when the logout button is clicked', async () => {
    const { logout } = await import('../../api/auth');
    renderLayout();
    await userEvent.click(screen.getByRole('button', { name: /log out/i }));
    expect(logout).toHaveBeenCalledOnce();
  });
});
