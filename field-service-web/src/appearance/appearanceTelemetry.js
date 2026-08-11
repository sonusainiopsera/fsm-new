/**
 * Emits an appearance-changed telemetry event.
 *
 * No PII is included. The event name and payload keys are stable so dashboards
 * can aggregate without schema migrations.
 *
 * @param {string} role            User's role (e.g. 'DISPATCHER') — not PII
 * @param {string} newPreference   The preference the user chose (e.g. 'DARK')
 */
export function emitAppearanceChanged(role, newPreference) {
  if (typeof window === 'undefined') return;
  try {
    window.dispatchEvent(
      new CustomEvent('fsvc:appearance-changed', {
        detail: {
          role,
          newPreference,
          timestamp: Date.now(),
        },
      })
    );
  } catch {
    // Telemetry must never break the calling code
  }
}
