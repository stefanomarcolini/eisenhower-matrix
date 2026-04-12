import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { PasswordWarningBanner } from '../PasswordWarningBanner';

function renderBanner(onDismiss = vi.fn()) {
  render(
    <MemoryRouter>
      <PasswordWarningBanner onDismiss={onDismiss} />
    </MemoryRouter>,
  );
  return { onDismiss };
}

describe('PasswordWarningBanner', () => {
  it('renders the warning message', () => {
    renderBanner();
    expect(screen.getByTestId('password-warning-banner')).toBeInTheDocument();
    expect(screen.getByText(/password is over 80 days old/i)).toBeInTheDocument();
  });

  it('renders a link to /settings', () => {
    renderBanner();
    expect(screen.getByRole('link', { name: /settings/i })).toHaveAttribute('href', '/settings');
  });

  it('calls onDismiss when the dismiss button is clicked', async () => {
    const { onDismiss } = renderBanner();
    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(onDismiss).toHaveBeenCalledOnce();
  });
});
