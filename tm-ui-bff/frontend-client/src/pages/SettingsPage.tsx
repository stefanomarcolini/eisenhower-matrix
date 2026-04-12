import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useProfile, useUpdateProfile, useChangePassword, useInitMfaEnrollment, useDisableMfa } from '../hooks/useProfile';
import { PasswordStrengthIndicator } from '../components/PasswordStrengthIndicator';
import { MfaEnrollDialog } from '../components/MfaEnrollDialog';
import type { MfaEnrollResp } from '../api/profile';

// --- Profile section -------------------------------------------------------

interface ProfileForm {
  displayName: string;
  theme: 'LIGHT' | 'DARK';
}

function ProfileSection() {
  const { data: profile } = useProfile();
  const updateProfile     = useUpdateProfile();

  const { register, handleSubmit, formState: { isDirty } } = useForm<ProfileForm>({
    values: { displayName: profile?.displayName ?? '', theme: profile?.theme ?? 'LIGHT' },
  });

  function onSubmit(values: ProfileForm) {
    updateProfile.mutate({
      displayName: values.displayName || null,
      theme: values.theme,
    });
  }

  return (
    <section data-testid="profile-section" className="space-y-4">
      <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Profile</h2>
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4 max-w-sm">
        <div>
          <label htmlFor="display-name" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
            Display Name
          </label>
          <input
            id="display-name"
            {...register('displayName')}
            placeholder={profile?.email ?? ''}
            className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
        </div>

        <div>
          <label htmlFor="theme-select" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
            Theme
          </label>
          <select
            id="theme-select"
            {...register('theme')}
            className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          >
            <option value="LIGHT">Light</option>
            <option value="DARK">Dark</option>
          </select>
        </div>

        <button
          type="submit"
          disabled={!isDirty || updateProfile.isPending}
          className="px-4 py-2 text-sm rounded-md bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {updateProfile.isPending ? 'Saving…' : 'Save'}
        </button>
        {updateProfile.isSuccess && (
          <p className="text-sm text-green-600 dark:text-green-400">Saved.</p>
        )}
      </form>
    </section>
  );
}

// --- Change Password section -----------------------------------------------

interface PasswordForm {
  currentPassword: string;
  newPassword:     string;
  confirmPassword: string;
}

function ChangePasswordSection() {
  const changePassword = useChangePassword();
  const { register, handleSubmit, watch, reset, formState: { errors } } = useForm<PasswordForm>();
  const newPassword = watch('newPassword', '');

  function onSubmit(values: PasswordForm) {
    changePassword.mutate(
      { currentPassword: values.currentPassword, newPassword: values.newPassword },
      { onSuccess: () => reset() },
    );
  }

  return (
    <section data-testid="change-password-section" className="space-y-4">
      <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">Change Password</h3>
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4 max-w-sm">
        <div>
          <label htmlFor="current-password" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
            Current Password
          </label>
          <input
            id="current-password"
            type="password"
            {...register('currentPassword', { required: 'Required' })}
            className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          {errors.currentPassword && (
            <p role="alert" className="mt-1 text-xs text-red-600">{errors.currentPassword.message}</p>
          )}
        </div>

        <div>
          <label htmlFor="new-password" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
            New Password
          </label>
          <input
            id="new-password"
            type="password"
            {...register('newPassword', { required: 'Required' })}
            className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          <PasswordStrengthIndicator password={newPassword} />
          {errors.newPassword && (
            <p role="alert" className="mt-1 text-xs text-red-600">{errors.newPassword.message}</p>
          )}
        </div>

        <div>
          <label htmlFor="confirm-new-password" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
            Confirm New Password
          </label>
          <input
            id="confirm-new-password"
            type="password"
            {...register('confirmPassword', {
              required: 'Required',
              validate: (v) => v === newPassword || 'Passwords do not match',
            })}
            className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
          />
          {errors.confirmPassword && (
            <p role="alert" className="mt-1 text-xs text-red-600">{errors.confirmPassword.message}</p>
          )}
        </div>

        {changePassword.isError && (
          <p role="alert" className="text-sm text-red-600 dark:text-red-400">
            Incorrect current password or password does not meet requirements.
          </p>
        )}
        {changePassword.isSuccess && (
          <p className="text-sm text-green-600 dark:text-green-400">Password changed successfully.</p>
        )}

        <button
          type="submit"
          disabled={changePassword.isPending}
          className="px-4 py-2 text-sm rounded-md bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {changePassword.isPending ? 'Saving…' : 'Change Password'}
        </button>
      </form>
    </section>
  );
}

// --- MFA section -----------------------------------------------------------

interface MfaSectionProps {
  isMfaEnabled: boolean;
}

function MfaSection({ isMfaEnabled }: MfaSectionProps) {
  const initMfa    = useInitMfaEnrollment();
  const disableMfa = useDisableMfa();
  const [enrollData,      setEnrollData]      = useState<MfaEnrollResp | null>(null);
  const [showDisableForm, setShowDisableForm] = useState(false);

  const { register, handleSubmit, reset, formState: { errors } } = useForm<{ code: string }>();

  function handleEnable() {
    initMfa.mutate(undefined, { onSuccess: (data) => setEnrollData(data) });
  }

  function handleDisable({ code }: { code: string }) {
    disableMfa.mutate({ code }, { onSuccess: () => { setShowDisableForm(false); reset(); } });
  }

  return (
    <section data-testid="mfa-section" className="space-y-3">
      <h3 className="text-sm font-semibold text-gray-700 dark:text-gray-300">
        Two-Factor Authentication (TOTP)
      </h3>

      {isMfaEnabled ? (
        <>
          <p className="text-sm text-green-600 dark:text-green-400 font-medium">
            ✓ MFA is enabled
          </p>
          {!showDisableForm ? (
            <button
              type="button"
              onClick={() => setShowDisableForm(true)}
              className="text-sm text-red-600 dark:text-red-400 hover:underline"
            >
              Disable MFA
            </button>
          ) : (
            <form onSubmit={handleSubmit(handleDisable)} className="flex items-start gap-2 max-w-xs">
              <div className="flex-1">
                <input
                  inputMode="numeric"
                  maxLength={6}
                  placeholder="6-digit code"
                  {...register('code', {
                    required: 'Required',
                    pattern:  { value: /^\d{6}$/, message: 'Must be 6 digits' },
                  })}
                  className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500"
                />
                {(errors.code || disableMfa.isError) && (
                  <p role="alert" className="mt-1 text-xs text-red-600">
                    {errors.code?.message ?? 'Invalid code'}
                  </p>
                )}
              </div>
              <button
                type="submit"
                disabled={disableMfa.isPending}
                className="px-3 py-2 text-sm rounded-md bg-red-600 text-white hover:bg-red-700 disabled:opacity-50"
              >
                Confirm
              </button>
              <button
                type="button"
                onClick={() => { setShowDisableForm(false); reset(); }}
                className="px-3 py-2 text-sm rounded-md border border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-300"
              >
                Cancel
              </button>
            </form>
          )}
        </>
      ) : (
        <>
          <p className="text-sm text-gray-500 dark:text-gray-400">MFA is not enabled.</p>
          <button
            type="button"
            onClick={handleEnable}
            disabled={initMfa.isPending}
            className="px-4 py-2 text-sm rounded-md bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            {initMfa.isPending ? 'Loading…' : 'Enable MFA'}
          </button>
        </>
      )}

      {enrollData && (
        <MfaEnrollDialog
          enrollData={enrollData}
          onClose={() => setEnrollData(null)}
        />
      )}
    </section>
  );
}

// --- SettingsPage ----------------------------------------------------------

export default function SettingsPage() {
  const { data: profile, isLoading } = useProfile();

  if (isLoading) {
    return <div className="p-8 text-center text-gray-500">Loading…</div>;
  }

  const isLocal = profile?.authProvider === 'LOCAL';

  return (
    <div className="max-w-2xl mx-auto px-4 py-8 space-y-10">
        <h1 className="text-2xl font-bold text-gray-900 dark:text-gray-100">Settings</h1>

        <ProfileSection />

        <hr className="border-gray-200 dark:border-gray-800" />

        <section className="space-y-8">
          <h2 className="text-base font-semibold text-gray-900 dark:text-gray-100">Security</h2>
          {isLocal && <ChangePasswordSection />}
          {profile && <MfaSection isMfaEnabled={profile.isMfaEnabled} />}
        </section>
    </div>
  );
}
