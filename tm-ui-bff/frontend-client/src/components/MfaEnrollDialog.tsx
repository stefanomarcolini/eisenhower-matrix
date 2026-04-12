import * as Dialog from '@radix-ui/react-dialog';
import QRCode from 'qrcode';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useConfirmMfaEnrollment } from '../hooks/useProfile';
import type { MfaEnrollResp } from '../api/profile';

interface Props {
  enrollData: MfaEnrollResp;
  onClose:    () => void;
}

interface VerifyForm {
  code: string;
}

/**
 * Two-step MFA enrollment dialog.
 * Step 1: User scans the QR code (or enters the secret manually).
 * Step 2: User confirms with a TOTP code from their authenticator app.
 * See AUTH_CONFIG.md §7.
 */
export function MfaEnrollDialog({ enrollData, onClose }: Readonly<Props>) {
  const confirm = useConfirmMfaEnrollment();
  const { register, handleSubmit, formState: { errors } } = useForm<VerifyForm>();
  const [qrCodeUrl, setQrCodeUrl] = useState<string | null>(null);
  const [qrCodeError, setQrCodeError] = useState(false);

  useEffect(() => {
    let cancelled = false;

    setQrCodeUrl(null);
    setQrCodeError(false);

    QRCode.toDataURL(enrollData.otpauthUri, {
      errorCorrectionLevel: 'M',
      margin: 1,
      width: 180,
    })
      .then((dataUrl: string) => {
        if (!cancelled) {
          setQrCodeUrl(dataUrl);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setQrCodeError(true);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [enrollData.otpauthUri]);

  function onSubmit({ code }: VerifyForm) {
    confirm.mutate({ code }, { onSuccess: onClose });
  }

  return (
    <Dialog.Root open onOpenChange={(open) => !open && onClose()}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 bg-black/40 dark:bg-black/60 z-40" />
        <Dialog.Content
          data-testid="mfa-enroll-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center p-4"
        >
          <div className="bg-white dark:bg-gray-900 rounded-xl shadow-xl w-full max-w-sm p-6">
            <div className="flex items-center justify-between mb-4">
              <Dialog.Title className="text-lg font-semibold text-gray-900 dark:text-gray-100">
                Enable Two-Factor Auth
              </Dialog.Title>
              <Dialog.Description className="sr-only">
                Scan the QR code or enter the secret, then confirm with a 6-digit authenticator code.
              </Dialog.Description>
              <Dialog.Close asChild>
                <button type="button" aria-label="Close" className="text-gray-400 hover:text-gray-600 dark:hover:text-gray-200">
                  <span aria-hidden="true" className="text-base leading-none">x</span>
                </button>
              </Dialog.Close>
            </div>

            <p className="text-sm text-gray-600 dark:text-gray-400 mb-4">
              Scan this QR code with your authenticator app (e.g. Google Authenticator, Authy).
            </p>

            {/* QR code */}
            <div className="flex justify-center mb-4 p-3 bg-white rounded-lg border border-gray-200 min-h-[206px] items-center">
              {qrCodeUrl ? (
                <img
                  src={qrCodeUrl}
                  alt="Scan this QR code with your authenticator app"
                  width={180}
                  height={180}
                  data-testid="mfa-qr-code"
                />
              ) : (
                <div className="text-center text-sm text-gray-500" data-testid="mfa-qr-fallback">
                  <p>{qrCodeError ? 'QR preview unavailable.' : 'Generating QR code…'}</p>
                  <p className="mt-2 text-xs">You can still use the manual secret below.</p>
                </div>
              )}
            </div>

            {/* Manual entry fallback */}
            <details className="mb-5 text-xs text-gray-500 dark:text-gray-400">
              <summary className="cursor-pointer select-none hover:text-gray-700 dark:hover:text-gray-300">
                Can't scan? Enter code manually
              </summary>
              <p className="mt-2 break-all font-mono bg-gray-100 dark:bg-gray-800 px-2 py-1.5 rounded select-all">
                {enrollData.secret}
              </p>
            </details>

            {/* TOTP verification */}
            <form onSubmit={handleSubmit(onSubmit)} className="space-y-3">
              <div>
                <label htmlFor="mfa-code" className="block text-sm font-medium text-gray-700 dark:text-gray-300 mb-1">
                  Enter 6-digit code to confirm
                </label>
                <input
                  id="mfa-code"
                  inputMode="numeric"
                  maxLength={6}
                  {...register('code', {
                    required: 'Code is required',
                    pattern:  { value: /^\d{6}$/, message: 'Must be 6 digits' },
                  })}
                  className="w-full rounded-md border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 px-3 py-2 text-sm text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500 tracking-widest text-center"
                />
                {errors.code && (
                  <p role="alert" className="mt-1 text-xs text-red-600 dark:text-red-400">{errors.code.message}</p>
                )}
                {confirm.isError && (
                  <p role="alert" className="mt-1 text-xs text-red-600 dark:text-red-400">Invalid code — please try again.</p>
                )}
              </div>
              <button
                type="submit"
                disabled={confirm.isPending}
                className="w-full py-2 text-sm rounded-md bg-indigo-600 text-white hover:bg-indigo-700 disabled:opacity-50"
              >
                {confirm.isPending ? 'Verifying…' : 'Activate MFA'}
              </button>
            </form>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
