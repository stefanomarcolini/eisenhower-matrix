import { Navigate } from 'react-router-dom';
import { useSession } from '../hooks/useSession';

interface Props {
  children: React.ReactNode;
  requiredRole?: string;
}

/**
 * Auth guard — wraps routes that require an authenticated session.
 * Redirects to /login if not authenticated, /mfa/verify if MFA is pending.
 * See CODING_PATTERNS.md §10.
 */
export function ProtectedRoute({ children, requiredRole }: Props) {
  const { data: session, isLoading } = useSession();

  if (isLoading) {
    return (
      <div className="flex h-screen items-center justify-center">
        <span className="animate-spin h-8 w-8 rounded-full border-4 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (!session?.isAuthenticated) {
    return <Navigate to="/login" replace />;
  }

  if (session.mfaPending) {
    return <Navigate to="/mfa/verify" replace />;
  }

  if (requiredRole && session.role !== requiredRole) {
    return <Navigate to="/dashboard" replace />;
  }

  return <>{children}</>;
}
