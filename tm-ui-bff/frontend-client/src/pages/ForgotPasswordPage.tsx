import { useForm } from 'react-hook-form';
import { Link } from 'react-router-dom';
import { useMutation } from '@tanstack/react-query';
import { forgotPassword } from '../api/auth';

interface ForgotForm {
  email: string;
}

/**
 * Forgot-password page.
 * Always shows "check your email" after submit — even for unknown addresses —
 * to prevent email enumeration (AUTH_CONFIG.md §9, PASSWORD_POLICY.md §4).
 */
export default function ForgotPasswordPage() {
  const { register, handleSubmit, formState: { errors } } = useForm<ForgotForm>();

  const mutation = useMutation({
    mutationFn: ({ email }: ForgotForm) => forgotPassword(email),
  });

  if (mutation.isSuccess) {
    return (
      <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 px-4">
        <div className="w-full max-w-sm text-center space-y-4">
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Check your email</h1>
          <p className="text-sm text-gray-600 dark:text-gray-400">
            If an account with that address exists, we've sent a password reset link.
          </p>
          <Link to="/login" className="inline-block text-sm font-medium text-blue-600 hover:underline">
            Back to sign in
          </Link>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 px-4">
      <div className="w-full max-w-sm space-y-6">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Forgot password?</h1>
          <p className="mt-2 text-sm text-gray-600 dark:text-gray-400">
            Enter your email and we'll send a reset link.
          </p>
        </div>

        <form onSubmit={handleSubmit((data) => mutation.mutate(data))} className="space-y-4" noValidate>
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

          <button
            type="submit"
            disabled={mutation.isPending}
            className="w-full rounded bg-blue-600 px-4 py-2 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Sending…' : 'Send reset link'}
          </button>
        </form>

        <p className="text-center text-sm text-gray-600 dark:text-gray-400">
          <Link to="/login" className="font-medium text-blue-600 hover:underline">Back to sign in</Link>
        </p>
      </div>
    </div>
  );
}
