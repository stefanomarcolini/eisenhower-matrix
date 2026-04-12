import { useState } from 'react';
import type { AxiosError } from 'axios';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { register as registerUser } from '../api/auth';
import { PasswordStrengthIndicator } from '../components/PasswordStrengthIndicator';

interface RegisterForm {
  email:           string;
  password:        string;
  confirmPassword: string;
}

/**
 * Registration page — email + password with strength indicator.
 * Strength indicator is informational only; server validates the actual rules.
 * See AUTH_CONFIG.md §6, PASSWORD_POLICY.md §1.
 */
export default function RegisterPage() {
  const navigate    = useNavigate();
  const queryClient = useQueryClient();
  const [apiError, setApiError] = useState<string | null>(null);

  const { register, handleSubmit, watch, formState: { errors } } = useForm<RegisterForm>();
  const password = watch('password', '');

  const mutation = useMutation({
    mutationFn: ({ email, password }: RegisterForm) => registerUser(email, password),
    onSuccess: async () => {
      // Warm the session cache so ProtectedRoute sees isAuthenticated:true
      // immediately on first render. The try/finally ensures navigate is always
      // called even if refetchQueries rejects (TanStack Query v5 re-throws when
      // an active observer's fetch fails).
      try {
        await queryClient.refetchQueries({ queryKey: ['session'] });
      } catch {
        // Ignore — navigate regardless; ProtectedRoute will fetch the session itself
      }
      navigate('/dashboard', { replace: true });
    },
    onError: (err: AxiosError<{ title?: string; detail?: string }>) => {
      setApiError(err.response?.data?.detail ?? err.response?.data?.title ?? 'Registration failed. Please try a different email.');
    },
  });

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 px-4">
      <div className="w-full max-w-sm space-y-6">
        <h1 className="text-2xl font-bold text-center text-gray-900 dark:text-white">Create account</h1>

        <form
          onSubmit={handleSubmit((data) => { setApiError(null); mutation.mutate(data); })}
          className="space-y-4"
          noValidate
        >
          <div>
            <label htmlFor="email" className="block text-sm font-medium text-gray-700 dark:text-gray-300">
              Email
            </label>
            <input
              id="email"
              type="email"
              autoComplete="email"
              {...register('email', {
                required: 'Email is required',
                pattern:  { value: /\S+@\S+\.\S+/, message: 'Invalid email address' },
              })}
              className="mt-1 w-full rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            {errors.email && (
              <p role="alert" className="mt-1 text-xs text-red-600">{errors.email.message}</p>
            )}
          </div>

          <div>
            <label htmlFor="password" className="block text-sm font-medium text-gray-700 dark:text-gray-300">
              Password
            </label>
            <input
              id="password"
              type="password"
              autoComplete="new-password"
              {...register('password', {
                required:  'Password is required',
                validate: {
                  minLength:   (v) => v.length >= 8          || 'Password must be at least 8 characters',
                  hasUpper:    (v) => /[A-Z]/.test(v)        || 'Password must contain at least one uppercase letter',
                  hasLower:    (v) => /[a-z]/.test(v)        || 'Password must contain at least one lowercase letter',
                  hasDigit:    (v) => /[0-9]/.test(v)        || 'Password must contain at least one digit',
                  hasSpecial:  (v) => /[!@#$%^&*()\-_=+[\]{}|;':",./<>?]/.test(v)
                                   || 'Password must contain at least one special character',
                },
              })}
              className="mt-1 w-full rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            <PasswordStrengthIndicator password={password} />
            {errors.password && (
              <p role="alert" className="mt-1 text-xs text-red-600">{errors.password.message}</p>
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
                validate: (value) => value === password || 'Passwords do not match',
              })}
              className="mt-1 w-full rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            {errors.confirmPassword && (
              <p role="alert" className="mt-1 text-xs text-red-600">{errors.confirmPassword.message}</p>
            )}
          </div>

          {apiError && (
            <p role="alert" className="text-sm text-red-600">{apiError}</p>
          )}

          <button
            type="submit"
            disabled={mutation.isPending}
            className="w-full rounded bg-blue-600 px-4 py-2 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p className="text-center text-sm text-gray-600 dark:text-gray-400">
          Already have an account?{' '}
          <Link to="/login" className="font-medium text-blue-600 hover:underline">Sign in</Link>
        </p>
      </div>
    </div>
  );
}
