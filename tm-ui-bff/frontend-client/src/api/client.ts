import axios from 'axios';

/**
 * Axios instance for all BFF calls.
 * CSRF interceptor: reads XSRF-TOKEN cookie (set by CookieCsrfTokenRepository)
 * and sends it as X-XSRF-TOKEN on all state-changing requests.
 * See AUTH_CONFIG.md §13 and CODING_PATTERNS.md §10.
 */
export const apiClient = axios.create({
  baseURL: '',          // relative — same origin (proxied through Vite dev server or BFF)
  withCredentials: true, // include session cookie on all requests
  headers: {
    'Content-Type': 'application/json',
  },
});

// CSRF token interceptor
apiClient.interceptors.request.use((config) => {
  const method = config.method?.toUpperCase();
  if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method ?? '')) {
    const csrfToken = getCsrfToken();
    if (csrfToken) {
      config.headers['X-XSRF-TOKEN'] = csrfToken;
    }
  }
  return config;
});

function getCsrfToken(): string | null {
  const match = document.cookie
    .split('; ')
    .find((row) => row.startsWith('XSRF-TOKEN='));
  return match ? decodeURIComponent(match.split('=')[1]) : null;
}
