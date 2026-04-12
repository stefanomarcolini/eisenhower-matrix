import { useForm } from 'react-hook-form';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { resetPassword } from '../api/auth';
import { PasswordStrengthIndicator } from '../components/PasswordStrengthIndicator';

interface ResetForm {
  newPassword:     string;
  confirmPassword: string;
}

/**
 * Reset-password page.
 * Reads the one-time token from the ?token= query parameter in the reset email link.
 * See AUTH_CONFIG.md §9, PASSWORD_POLICY.md §4.
 */
export default function ResetPasswordPage() {
  const [searchParams]    = useSearchParams();
  const token             = searchParams.get('token') ?? '';
  const navigate          = useNavigate();
  const { register, handleSubmit, watch, formState: { errors } } = useForm<ResetForm>();
  const newPassword = watch('newPassword', '');

  const mutation = useMutation({
    mutationFn: ({ newPassword }: ResetForm) => resetPassword(token, newPassword),
    onSuccess: () => navigate('/login', { state: { resetSuccess: true } }),
  });

  if (!token) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 px-4">
        <div className="w-full max-w-sm text-center space-y-4">
          <p className="text-sm text-red-600">Invalid or missing reset token.</p>
          <Link to="/forgot-password" className="text-sm font-medium text-blue-600 hover:underline">
            Request a new link
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 px-4">
      <div className="w-full max-w-sm space-y-6">
        <h1 className="text-2xl font-bold text-center text-gray-900 dark:text-white">Set new password</h1>

        <form onSubmit={handleSubmit((data) => mutation.mutate(data))} className="space-y-4" noValidate>
          <div>
            <label htmlFor="newPassword" className="block text-sm font-medium text-gray-700 dark:text-gray-300">
              New password
            </label>
            <input
              id="newPassword"
              type="password"
              autoComplete="new-password"
              {...register('newPassword', {
                required:  'Password is required',
                minLength: { value: 8, message: 'Password must be at least 8 characters' },
              })}
              className="mt-1 w-full rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <PasswordStrengthIndicator password={newPassword} />
            {errors.newPassword && (
              <p role="alert" className="mt-1 text-xs text-red-600">{errors.newPassword.message}</p>
            )}
          </div>

          <div>
            <label htmlFor="confirmPassword" className="block text-sm font-medium text-gray-700 dark:text-gray-300">
              Confirm password
            </label>
            <input
              id="confirmPassword"
              type="password"
              autoComplete="new-password"
              {...register('confirmPassword', {
                required: 'Please confirm your password',
                validate: (value) => value === newPassword || 'Passwords do not match',
              })}
              className="mt-1 w-full rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            {errors.confirmPassword && (
              <p role="alert" className="mt-1 text-xs text-red-600">{errors.confirmPassword.message}</p>
            )}
          </div>

          {mutation.isError && (
            <p role="alert" className="text-sm text-red-600">
              Reset link is invalid or expired.{' '}
              <Link to="/forgot-password" className="underline">Request a new one.</Link>
            </p>
          )}

          <button
            type="submit"
            disabled={mutation.isPending}
            className="w-full rounded bg-blue-600 px-4 py-2 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Saving…' : 'Set new password'}
          </button>
        </form>
      </div>
    </div>
  );
}
