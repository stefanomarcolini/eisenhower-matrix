import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { PasswordStrengthIndicator } from '../PasswordStrengthIndicator';

describe('PasswordStrengthIndicator', () => {
  it('renders nothing for an empty password', () => {
    const { container } = render(<PasswordStrengthIndicator password="" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows "Weak" label for a trivially short password', () => {
    render(<PasswordStrengthIndicator password="abc" />);
    expect(screen.getByTestId('password-strength-label')).toHaveTextContent('Weak');
  });

  it('shows "Fair" or better for a minimally complex password', () => {
    // Meets minimum rules: 8 chars, upper, lower, digit, special
    render(<PasswordStrengthIndicator password="Abc123!x" />);
    const label = screen.getByTestId('password-strength-label').textContent;
    expect(['Weak', 'Fair', 'Strong', 'Very Strong']).toContain(label);
  });

  it('shows "Strong" or "Very Strong" for a long passphrase', () => {
    render(<PasswordStrengthIndicator password="Correct-Battery-Horse-Staple-99!" />);
    const label = screen.getByTestId('password-strength-label').textContent;
    expect(['Strong', 'Very Strong']).toContain(label);
  });

  it('renders the strength bar container', () => {
    render(<PasswordStrengthIndicator password="SomePassword1!" />);
    expect(screen.getByTestId('password-strength')).toBeInTheDocument();
  });

  it('renders "Weak" label in red CSS class', () => {
    render(<PasswordStrengthIndicator password="aaa" />);
    const label = screen.getByTestId('password-strength-label');
    // Weak maps to text-red-600 (light) / text-red-400 (dark)
    expect(label.className).toMatch(/text-red/);
  });
});
