import { Link, NavLink, Outlet } from 'react-router-dom';
import { LogOut, LayoutGrid, Settings } from 'lucide-react';
import { useSession, useLogout } from '../hooks/useSession';
import { useProfile } from '../hooks/useProfile';

/**
 * Shared layout for all authenticated pages.
 * Renders a sticky top navbar (brand, nav links, user avatar, logout)
 * and renders the current route via <Outlet />.
 *
 * Wrap protected routes with this component via the nested-route pattern
 * in App.tsx — not used directly by page components.
 */
export function AppLayout() {
  const { data: session } = useSession();
  const { data: profile } = useProfile();
  const logout = useLogout();

  const displayName = profile?.displayName || session?.email || '';

  // Up to two initials from the display name; falls back to first char of email prefix
  const initials = displayName
    .split(/\s+/)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase() ?? '')
    .join('') || '?';

  const navLinkClass = ({ isActive }: { isActive: boolean }) =>
    `flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm font-medium transition-colors ${
      isActive
        ? 'bg-indigo-50 dark:bg-indigo-950 text-indigo-700 dark:text-indigo-300'
        : 'text-gray-600 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 hover:bg-gray-100 dark:hover:bg-gray-800'
    }`;

  return (
    <div className="min-h-screen bg-gray-50 dark:bg-gray-950 flex flex-col">

      {/* ── Navigation bar ──────────────────────────────────────────────── */}
      <nav className="sticky top-0 z-40 bg-white dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 h-14 flex items-center justify-between">

          {/* Left: brand mark + nav links */}
          <div className="flex items-center gap-5">
            <Link
              to="/dashboard"
              className="flex items-center gap-2 select-none"
              aria-label="Task Manager home"
            >
              <span className="inline-flex h-7 w-7 items-center justify-center rounded-md bg-indigo-600 text-white text-xs font-bold tracking-tight">
                TM
              </span>
              <span className="hidden sm:inline text-sm font-semibold text-gray-900 dark:text-gray-100">
                Task Manager
              </span>
            </Link>

            <div className="flex items-center gap-1">
              <NavLink to="/dashboard" className={navLinkClass}>
                <LayoutGrid size={15} />
                Dashboard
              </NavLink>
              <NavLink to="/settings" className={navLinkClass}>
                <Settings size={15} />
                Settings
              </NavLink>
            </div>
          </div>

          {/* Right: avatar + display name + logout */}
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-2">
              <span
                aria-hidden
                className="inline-flex h-8 w-8 items-center justify-center rounded-full bg-indigo-100 dark:bg-indigo-900 text-xs font-semibold text-indigo-700 dark:text-indigo-300 select-none"
              >
                {initials}
              </span>
              <span className="hidden md:block text-sm text-gray-700 dark:text-gray-300 max-w-[180px] truncate">
                {displayName}
              </span>
            </div>

            <button
              type="button"
              onClick={() => logout.mutate()}
              disabled={logout.isPending}
              aria-label="Log out"
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-md text-sm text-gray-600 dark:text-gray-400 hover:text-red-600 dark:hover:text-red-400 hover:bg-gray-100 dark:hover:bg-gray-800 transition-colors disabled:opacity-50"
            >
              <LogOut size={15} />
              <span className="hidden sm:inline">Logout</span>
            </button>
          </div>

        </div>
      </nav>

      {/* ── Page content ────────────────────────────────────────────────── */}
      <main className="flex-1">
        <Outlet />
      </main>

    </div>
  );
}
