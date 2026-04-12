import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import {
  getProfile, updateProfile, changePassword,
  initMfaEnrollment, confirmMfaEnrollment, disableMfa,
} from '../api/profile';
import type { UpdateProfileReq, UserProfile } from '../api/profile';

export const PROFILE_KEY = ['profile'] as const;

export function useProfile() {
  return useQuery({
    queryKey: PROFILE_KEY,
    queryFn: getProfile,
    retry: false,     // 401 on public pages is expected — don't retry
    staleTime: 60_000,
  });
}

export function useUpdateProfile() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: updateProfile,
    onMutate: async (req: UpdateProfileReq) => {
      await qc.cancelQueries({ queryKey: PROFILE_KEY });
      const previous = qc.getQueryData<UserProfile>(PROFILE_KEY);

      if (previous) {
        qc.setQueryData<UserProfile>(PROFILE_KEY, {
          ...previous,
          displayName: req.displayName ?? previous.displayName,
          theme: req.theme ?? previous.theme,
        });
      }

      return { previous };
    },
    onError: (_err, _req, ctx) => {
      if (ctx?.previous) {
        qc.setQueryData(PROFILE_KEY, ctx.previous);
      }
    },
    onSuccess: (updated) => qc.setQueryData(PROFILE_KEY, updated),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: PROFILE_KEY });
    },
  });
}

export function useChangePassword() {
  return useMutation({ mutationFn: changePassword });
}

export function useInitMfaEnrollment() {
  return useMutation({ mutationFn: initMfaEnrollment });
}

export function useConfirmMfaEnrollment() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: confirmMfaEnrollment,
    onSuccess: () => qc.invalidateQueries({ queryKey: PROFILE_KEY }),
  });
}

export function useDisableMfa() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: disableMfa,
    onSuccess: () => qc.invalidateQueries({ queryKey: PROFILE_KEY }),
  });
}
