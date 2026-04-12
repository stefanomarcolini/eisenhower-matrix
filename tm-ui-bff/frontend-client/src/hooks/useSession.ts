import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';
import { apiClient } from '../api/client';
import { logout as logoutApi } from '../api/auth';

export interface SessionData {
  isAuthenticated: boolean;
  userId:          string | null;
  email:           string | null;
  tenantId:        string | null;
  role:            string | null;
  mfaPending:      boolean;
  passwordWarning: boolean;
}

/**
 * Fetches the current BFF session state.
 * Used by ProtectedRoute and the password-warning banner.
 * Cached by TanStack Query — re-fetched on window focus and on explicit invalidation.
 * See CODING_PATTERNS.md §10.
 */
export function useSession() {
  return useQuery<SessionData>({
    queryKey: ['session'],
    queryFn: async () => {
      const res = await apiClient.get<SessionData>('/auth/session');
      return res.data;
    },
    staleTime: 30_000,    // consider fresh for 30 s — session timeout is 30 min
    retry: false,          // don't retry 401s — they mean the session is gone
  });
}

/**
 * POST /logout → invalidates the Spring Session, deletes TM_SESSION cookie.
 * On completion (success or network error) clears the local query cache and
 * sends the user to /login so the app is always in a clean state.
 */
export function useLogout() {
  const qc       = useQueryClient();
  const navigate = useNavigate();

  return useMutation({
    mutationFn: logoutApi,
    onSettled: () => {
      qc.clear();
      navigate('/login', { replace: true });
    },
  });
}
