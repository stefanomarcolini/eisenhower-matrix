import { apiClient } from './client';

/**
 * Default tenant for single-tenant dev deployments.
 * Matches app.default-tenant-id in application.yml.
 * Multi-tenant setups would derive this from subdomain or a config endpoint.
 */
export const DEFAULT_TENANT_ID = '00000000-0000-0000-0000-000000000001';

export async function login(
  email: string,
  password: string,
): Promise<{ mfaPending: boolean; passwordWarning: boolean }> {
  const res = await apiClient.post('/auth/local/login', {
    email,
    password,
    tenantId: DEFAULT_TENANT_ID,
  });
  return res.data;
}

export async function register(
  email: string,
  password: string,
): Promise<{ userId: string; tenantId: string; role: string }> {
  const res = await apiClient.post('/auth/local/register', {
    email,
    password,
    tenantId: DEFAULT_TENANT_ID,
  });
  return res.data;
}

export async function forgotPassword(email: string): Promise<void> {
  await apiClient.post('/auth/forgot-password', {
    email,
    tenantId: DEFAULT_TENANT_ID,
  });
}

export async function resetPassword(token: string, newPassword: string): Promise<void> {
  await apiClient.post('/auth/reset-password', { token, newPassword });
}

export async function verifyMfa(code: string): Promise<void> {
  await apiClient.post('/auth/mfa/verify', { code });
}

export async function logout(): Promise<void> {
  await apiClient.post('/logout');
}
