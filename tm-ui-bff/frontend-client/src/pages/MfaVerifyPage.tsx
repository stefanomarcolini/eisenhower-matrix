import { useForm } from 'react-hook-form';
import { Navigate, useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { verifyMfa } from '../api/auth';
import { useSession } from '../hooks/useSession';

interface MfaForm {
  code: string;
}

/**
 * MFA TOTP verification page.
 * Only reachable when the session has mfaPending = true.
 * See AUTH_CONFIG.md §7.
 */
export default function MfaVerifyPage() {
  const { data: session, isLoading } = useSession();
  const navigate    = useNavigate();
  const queryClient = useQueryClient();
  const { register, handleSubmit, formState: { errors } } = useForm<MfaForm>();

  const mutation = useMutation({
    mutationFn: ({ code }: MfaForm) => verifyMfa(code),
    onSuccess: async () => {
      // Warm the session cache so ProtectedRoute sees the full post-MFA session
      // before navigation. invalidateQueries alone keeps stale mfaPending data
      // in cache, which can bounce the user back to /login on mount.
      try {
        await queryClient.refetchQueries({ queryKey: ['session'] });
      } catch {
        // Ignore — navigate regardless; ProtectedRoute will fetch the session itself
      }
      navigate('/dashboard', { replace: true });
    },
  });

  if (isLoading) return null;
  if (!session?.mfaPending && !session?.isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-gray-50 dark:bg-gray-900 px-4">
      <div className="w-full max-w-sm space-y-6">
        <div className="text-center">
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Two-factor authentication</h1>
          <p className="mt-2 text-sm text-gray-600 dark:text-gray-400">
            Enter the 6-digit code from your authenticator app.
          </p>
        </div>

        <form onSubmit={handleSubmit((data) => mutation.mutate(data))} className="space-y-4" noValidate>
          <div>
            <label htmlFor="code" className="block text-sm font-medium text-gray-700 dark:text-gray-300">
              Verification code
            </label>
            <input
              id="code"
              type="text"
              inputMode="numeric"
              autoComplete="one-time-code"
              maxLength={6}
              {...register('code', {
                required: 'Code is required',
                pattern:  { value: /^\d{6}$/, message: 'Must be a 6-digit code' },
              })}
              className="mt-1 w-full rounded border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-center text-2xl tracking-widest text-gray-900 dark:text-white focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
            {errors.code && (
              <p role="alert" className="mt-1 text-xs text-red-600">{errors.code.message}</p>
            )}
          </div>

          {mutation.isError && (
            <p role="alert" className="text-sm text-red-600">Invalid code. Please try again.</p>
          )}

          <button
            type="submit"
            disabled={mutation.isPending}
            className="w-full rounded bg-blue-600 px-4 py-2 text-white font-medium hover:bg-blue-700 disabled:opacity-50"
          >
            {mutation.isPending ? 'Verifying…' : 'Verify'}
          </button>
        </form>
      </div>
    </div>
  );
}
