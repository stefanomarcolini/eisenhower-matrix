import { zxcvbn, zxcvbnOptions } from '@zxcvbn-ts/core';
import * as zxcvbnCommonPackage from '@zxcvbn-ts/language-common';
import * as zxcvbnEnPackage from '@zxcvbn-ts/language-en';

/**
 * Load language data at module initialisation time.
 * Required for meaningful dictionary-based scoring — without these packages,
 * zxcvbn returns no dictionary data and all passwords score 0.
 * See PASSWORD_POLICY.md §1.
 */
zxcvbnOptions.setOptions({
  translations: zxcvbnEnPackage.translations,
  graphs: zxcvbnCommonPackage.adjacencyGraphs,
  dictionary: {
    ...zxcvbnCommonPackage.dictionary,
    ...zxcvbnEnPackage.dictionary,
  },
});

/**
 * Maps zxcvbn's 0–4 score to the project's 4-band display scale (PASSWORD_POLICY.md §1).
 * 0–1 → Weak, 2 → Fair, 3 → Strong, 4 → Very Strong.
 */
function toLevel(score: number): 0 | 1 | 2 | 3 {
  if (score <= 1) return 0;
  if (score === 2) return 1;
  if (score === 3) return 2;
  return 3;
}

const LEVELS = [
  { label: 'Weak',        barColor: 'bg-red-500',     textColor: 'text-red-600 dark:text-red-400',         barWidth: 'w-1/4' },
  { label: 'Fair',        barColor: 'bg-orange-500',  textColor: 'text-orange-600 dark:text-orange-400',   barWidth: 'w-2/4' },
  { label: 'Strong',      barColor: 'bg-yellow-400',  textColor: 'text-yellow-600 dark:text-yellow-400',   barWidth: 'w-3/4' },
  { label: 'Very Strong', barColor: 'bg-green-500',   textColor: 'text-green-600 dark:text-green-400',     barWidth: 'w-full' },
] as const;

interface Props {
  password: string;
}

/**
 * Purely informational password strength bar + label.
 * Renders nothing when password is empty.
 * Does NOT block form submission — server-side rules are the gate.
 */
export function PasswordStrengthIndicator({ password }: Props) {
  if (!password) return null;

  const { score } = zxcvbn(password);
  const level = toLevel(score);
  const { label, barColor, textColor, barWidth } = LEVELS[level];

  return (
    <div className="mt-1 space-y-1" data-testid="password-strength">
      <div className="h-1.5 w-full rounded bg-gray-200 dark:bg-gray-700">
        <div
          className={`h-1.5 rounded transition-all duration-300 ${barColor} ${barWidth}`}
          aria-hidden="true"
        />
      </div>
      <p className={`text-xs font-medium ${textColor}`} data-testid="password-strength-label">
        {label}
      </p>
    </div>
  );
}
