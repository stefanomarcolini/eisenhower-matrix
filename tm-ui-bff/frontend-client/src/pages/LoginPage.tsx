import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { login } from '../api/auth';

interface LoginForm {
  email:    string;
  password: string;
}

/**
 * Login page — local email/password form + OAuth2 provider buttons.
 * On success: invalidates the session query so ProtectedRoute re-evaluates.
 * See AUTH_CONFIG.md §6, §7.
 */
export default function LoginPage() {
  const navigate     = useNavigate();
  const queryClient  = useQueryClient();
  const { register, handleSubmit, formState: { errors } } = useForm<LoginForm>();
  const isLocalHost = typeof globalThis.window !== 'undefined'
    && ['localhost', '127.0.0.1'].includes(globalThis.window.location.hostname);

  const mutation = useMutation({
    mutationFn: ({ email, password }: LoginForm) => login(email, password),
    onSuccess: async (data) => {
      // Await the session refetch so ProtectedRoute sees isAuthenticated:true
      // before the navigation happens. invalidateQueries alone keeps stale data
      // in cache, causing ProtectedRoute to redirect back to /login on mount.
      await queryClient.refetchQueries({ queryKey: ['session'] });
      navigate(data.mfaPending ? '/mfa/verify' : '/dashboard', { replace: true });
    },
  });

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 px-4">
      <div className="w-full max-w-sm space-y-6">
        <h1 className="text-2xl font-bold text-center text-gray-900 dark:text-white">Sign in</h1>

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

          <div>
            <label htmlFor="password" className="block text-sm font-medium text-gray-700 dark:text-gray-300">
              Password
            </label>
            <input
              id="password"
              type="password"
              autoComplete="current-password"
              {...register('password', { required: 'Password is required' })}
              className="mt-1 w-full rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            {errors.password && (
              <p role="alert" className="mt-1 text-xs text-red-600">{errors.password.message}</p>
            )}
          </div>

          {mutation.isError && (
            <p role="alert" className="text-sm text-red-600">Invalid email or password.</p>
          )}

          <button
            type="submit"
            disabled={mutation.isPending}
            className="w-full rounded bg-blue-600 px-4 py-2 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Signing in…' : 'Sign in'}
          </button>
        </form>

        {/* OAuth2 providers */}
        <div className="relative">
          <div className="absolute inset-0 flex items-center">
            <div className="w-full border-t border-gray-300 dark:border-gray-600" />
          </div>
          <div className="relative flex justify-center text-sm">
            <span className="bg-gray-50 dark:bg-gray-900 px-2 text-gray-500">or</span>
          </div>
        </div>

        <div className="space-y-2">
          <a
            href="/oauth2/authorization/google"
            className="flex w-full items-center justify-center rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-4 py-2 text-sm font-medium text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700"
          >
            Continue with Google
          </a>
          <a
            href="/oauth2/authorization/microsoft"
            className="flex w-full items-center justify-center rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-4 py-2 text-sm font-medium text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-700"
          >
            Continue with Microsoft
          </a>
          {isLocalHost && (
            <p className="text-xs text-center text-gray-500 dark:text-gray-400">
              Local development uses the mock OAuth2 provider. On the next screen, enter any
              email or subject and submit to complete sign-in.
            </p>
          )}
        </div>

        <p className="text-center text-sm text-gray-600 dark:text-gray-400">
          No account?{' '}
          <Link to="/register" className="font-medium text-blue-600 hover:underline">Register</Link>
          {' · '}
          <Link to="/forgot-password" className="font-medium text-blue-600 hover:underline">Forgot password?</Link>
        </p>
      </div>
    </div>
  );
}
