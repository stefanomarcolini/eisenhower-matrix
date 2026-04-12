import { apiClient } from './client';
import type { components } from './schema';

export type UserProfile       = components['schemas']['UserProfile'];
export type UpdateProfileReq  = components['schemas']['UpdateProfileRequest'];
export type ChangePasswordReq = components['schemas']['ChangePasswordRequest'];
export type MfaEnrollResp     = components['schemas']['MfaEnrollResponse'];
export type MfaCodeReq        = components['schemas']['MfaCodeRequest'];

export async function getProfile(): Promise<UserProfile> {
  const { data } = await apiClient.get<UserProfile>('/api/v1/users/me');
  return data;
}

export async function updateProfile(req: UpdateProfileReq): Promise<UserProfile> {
  const { data } = await apiClient.put<UserProfile>('/api/v1/users/me', req);
  return data;
}

export async function changePassword(req: ChangePasswordReq): Promise<void> {
  await apiClient.put('/api/v1/users/me/password', req);
}

export async function initMfaEnrollment(): Promise<MfaEnrollResp> {
  const { data } = await apiClient.post<MfaEnrollResp>('/api/v1/users/me/mfa/enable');
  return data;
}

export async function confirmMfaEnrollment(req: MfaCodeReq): Promise<void> {
  await apiClient.post('/api/v1/users/me/mfa/verify', req);
}

export async function disableMfa(req: MfaCodeReq): Promise<void> {
  await apiClient.delete('/api/v1/users/me/mfa', { data: req });
}
