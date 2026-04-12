import { useEffect } from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ProtectedRoute }  from './components/ProtectedRoute';
import { AppLayout }       from './components/AppLayout';
import LoginPage           from './pages/LoginPage';
import RegisterPage        from './pages/RegisterPage';
import MfaVerifyPage       from './pages/MfaVerifyPage';
import ForgotPasswordPage  from './pages/ForgotPasswordPage';
import ResetPasswordPage   from './pages/ResetPasswordPage';
import DashboardPage       from './pages/DashboardPage';
import SettingsPage        from './pages/SettingsPage';
import { useProfile }      from './hooks/useProfile';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: false,
      refetchOnWindowFocus: true,
    },
  },
});

/**
 * Applies the user's persisted theme (LIGHT/DARK) to <html> so Tailwind
 * dark: classes work everywhere. Runs inside QueryClientProvider so it can
 * use the profile query. See tailwind.config.ts (`darkMode: 'class'`).
 */
function ThemeSync() {
  const { data: profile } = useProfile();
  useEffect(() => {
    if (profile) {
      document.documentElement.classList.toggle('dark', profile.theme === 'DARK');
    }
  }, [profile]);
  return null;
}

// Placeholder page — replaced in a subsequent session
function AdminPage() { return <div className="p-8 text-xl">Admin</div>; }

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeSync />
      <BrowserRouter>
        <Routes>
          {/* Public routes */}
          <Route path="/login"               element={<LoginPage />} />
          <Route path="/register"            element={<RegisterPage />} />
          <Route path="/forgot-password"     element={<ForgotPasswordPage />} />
          <Route path="/auth/reset-password" element={<ResetPasswordPage />} />
          <Route path="/mfa/verify"          element={<MfaVerifyPage />} />

          {/* Protected routes — auth guard + shared nav layout */}
          <Route element={<ProtectedRoute><AppLayout /></ProtectedRoute>}>
            <Route path="/dashboard" element={<DashboardPage />} />
            <Route path="/settings/*" element={<SettingsPage />} />
            <Route path="/admin/*"    element={<AdminPage />} />
          </Route>

          {/* Default redirect */}
          <Route path="/"  element={<Navigate to="/dashboard" replace />} />
          <Route path="*"  element={<Navigate to="/dashboard" replace />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  );
}
