import { X } from 'lucide-react';
import { Link } from 'react-router-dom';

interface Props {
  onDismiss: () => void;
}

/**
 * Non-blocking banner shown when `passwordWarning` is true in the BFF session.
 * Dismissible per session. See PASSWORD_POLICY.md §3.
 */
export function PasswordWarningBanner({ onDismiss }: Props) {
  return (
    <div
      role="alert"
      data-testid="password-warning-banner"
      className="flex items-center justify-between gap-4 bg-amber-50 dark:bg-amber-900/30 border border-amber-200 dark:border-amber-700 text-amber-900 dark:text-amber-200 px-4 py-3 text-sm"
    >
      <span>
        Your password is over 80 days old. We recommend updating it in{' '}
        <Link
          to="/settings"
          className="underline font-medium hover:text-amber-700 dark:hover:text-amber-100"
        >
          Settings → Security
        </Link>
        .
      </span>
      <button
        type="button"
        onClick={onDismiss}
        aria-label="Dismiss password warning"
        className="shrink-0 text-amber-600 dark:text-amber-400 hover:text-amber-900 dark:hover:text-amber-100"
      >
        <X size={16} />
      </button>
    </div>
  );
}
