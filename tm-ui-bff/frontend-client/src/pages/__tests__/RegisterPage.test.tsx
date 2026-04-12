import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import RegisterPage from '../RegisterPage';

// Prevent real API calls
vi.mock('../../api/auth', () => ({
  register: vi.fn(),
}));

// PasswordStrengthIndicator uses @zxcvbn-ts; stub it out to keep tests fast
vi.mock('../../components/PasswordStrengthIndicator', () => ({
  PasswordStrengthIndicator: () => null,
}));

function renderRegister() {
  const qc = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <RegisterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('RegisterPage', () => {
  it('renders email, password, and confirm password inputs', () => {
    renderRegister();
    expect(screen.getByLabelText(/email/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^password$/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/confirm password/i)).toBeInTheDocument();
  });

  it('shows required validation errors when submitted empty', async () => {
    renderRegister();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    const alerts = await screen.findAllByRole('alert');
    expect(alerts.length).toBeGreaterThanOrEqual(3);
  });

  it('shows error when password is shorter than 8 characters', async () => {
    renderRegister();
    await userEvent.type(screen.getByLabelText(/^password$/i), 'Ab1!');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText(/at least 8 characters/i)).toBeInTheDocument();
  });

  it('shows error when password has no uppercase letter', async () => {
    renderRegister();
    // str0ng!pass#1 — lowercase + digit + special, no uppercase
    await userEvent.type(screen.getByLabelText(/^password$/i), 'str0ng!pass#1');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText(/uppercase letter/i)).toBeInTheDocument();
  });

  it('shows error when password has no lowercase letter', async () => {
    renderRegister();
    // STR0NG!PASS#1 — uppercase + digit + special, no lowercase
    await userEvent.type(screen.getByLabelText(/^password$/i), 'STR0NG!PASS#1');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText(/lowercase letter/i)).toBeInTheDocument();
  });

  it('shows error when password has no digit', async () => {
    renderRegister();
    // StrongPass!! — upper + lower + special, no digit
    await userEvent.type(screen.getByLabelText(/^password$/i), 'StrongPass!!');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText(/digit/i)).toBeInTheDocument();
  });

  it('shows error when password has no special character', async () => {
    renderRegister();
    // Str0ngPass01 — upper + lower + digit, no special character
    await userEvent.type(screen.getByLabelText(/^password$/i), 'Str0ngPass01');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText(/special character/i)).toBeInTheDocument();
  });

  it('shows error when passwords do not match', async () => {
    renderRegister();
    await userEvent.type(screen.getByLabelText(/^password$/i), 'Str0ng!Pass#1');
    await userEvent.type(screen.getByLabelText(/confirm password/i), 'Different!Pass1');
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));
    expect(await screen.findByText(/do not match/i)).toBeInTheDocument();
  });

  it('renders a link to the sign-in page', () => {
    renderRegister();
    expect(screen.getByRole('link', { name: /sign in/i })).toHaveAttribute('href', '/login');
  });
});
