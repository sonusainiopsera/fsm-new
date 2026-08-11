import { describe, it, expect } from 'vitest';
import {
  TECHNICIAN_PRESET,
  DISPATCHER_PRESET,
  MANAGER_PRESET,
  CUSTOMER_PRESET,
  PERSONA_PRESETS,
  PERSONA_NAMES,
  getPersonaPreset,
  resolvePersonaPreset,
  resolveColumnCount,
} from '../personaDensity.js';

describe('personaDensity — technician preset (field-first, BR-35)', () => {
  it('has minimum 44px touch target', () => {
    expect(TECHNICIAN_PRESET.touchTargetMin).toBeGreaterThanOrEqual(44);
  });

  it('has single column at every breakpoint', () => {
    expect(TECHNICIAN_PRESET.columnCount[360]).toBe(1);
    expect(TECHNICIAN_PRESET.columnCount[768]).toBe(1);
    expect(TECHNICIAN_PRESET.columnCount[1440]).toBe(1);
  });

  it('has bottom-anchored primary action', () => {
    expect(TECHNICIAN_PRESET.primaryActionPlacement).toBe('bottom');
  });

  it('uses system font stack token', () => {
    expect(TECHNICIAN_PRESET.fontStackToken).toBe('--font-sans');
  });

  it('requires 7:1 body contrast minimum', () => {
    expect(TECHNICIAN_PRESET.bodyContrastMin).toBe(7.0);
  });

  it('has comfortable density', () => {
    expect(TECHNICIAN_PRESET.density).toBe('comfortable');
  });

  it('resolves to 1 column at 360px viewport', () => {
    expect(resolveColumnCount(TECHNICIAN_PRESET, 360)).toBe(1);
  });
});

describe('personaDensity — dispatcher preset', () => {
  it('has compact density', () => {
    expect(DISPATCHER_PRESET.density).toBe('compact');
  });

  it('has 32px row height (highest density)', () => {
    expect(DISPATCHER_PRESET.rowHeight).toBe(32);
  });

  it('has drawer primary action placement', () => {
    expect(DISPATCHER_PRESET.primaryActionPlacement).toBe('drawer');
  });

  it('resolves to 4 columns at 1440px', () => {
    expect(resolveColumnCount(DISPATCHER_PRESET, 1440)).toBe(4);
  });

  it('resolves to 2 columns at 768px', () => {
    expect(resolveColumnCount(DISPATCHER_PRESET, 768)).toBe(2);
  });

  it('resolves to 1 column at 360px', () => {
    expect(resolveColumnCount(DISPATCHER_PRESET, 360)).toBe(1);
  });
});

describe('personaDensity — manager preset', () => {
  it('has generous card padding token', () => {
    expect(MANAGER_PRESET.cardPaddingToken).toBe('--space-8');
  });

  it('has top-bar primary action placement (chart-forward)', () => {
    expect(MANAGER_PRESET.primaryActionPlacement).toBe('top-bar');
  });

  it('has comfortable density', () => {
    expect(MANAGER_PRESET.density).toBe('comfortable');
  });

  it('resolves to 3 columns at 1440px', () => {
    expect(resolveColumnCount(MANAGER_PRESET, 1440)).toBe(3);
  });
});

describe('personaDensity — customer preset', () => {
  it('has most spacious row height', () => {
    expect(CUSTOMER_PRESET.rowHeight).toBeGreaterThan(TECHNICIAN_PRESET.rowHeight);
    expect(CUSTOMER_PRESET.rowHeight).toBeGreaterThan(DISPATCHER_PRESET.rowHeight);
    expect(CUSTOMER_PRESET.rowHeight).toBeGreaterThan(MANAGER_PRESET.rowHeight);
  });

  it('has 44px touch target minimum', () => {
    expect(CUSTOMER_PRESET.touchTargetMin).toBeGreaterThanOrEqual(44);
  });

  it('has comfortable density', () => {
    expect(CUSTOMER_PRESET.density).toBe('comfortable');
  });

  it('resolves to 1 column at 360px and 768px', () => {
    expect(resolveColumnCount(CUSTOMER_PRESET, 360)).toBe(1);
    expect(resolveColumnCount(CUSTOMER_PRESET, 768)).toBe(1);
  });
});

describe('resolvePersonaPreset', () => {
  it('returns technician preset for TECHNICIAN role', () => {
    expect(resolvePersonaPreset(['TECHNICIAN'])).toBe(TECHNICIAN_PRESET);
  });

  it('returns dispatcher preset for DISPATCHER role', () => {
    expect(resolvePersonaPreset(['DISPATCHER'])).toBe(DISPATCHER_PRESET);
  });

  it('returns manager preset for MANAGER role', () => {
    expect(resolvePersonaPreset(['MANAGER'])).toBe(MANAGER_PRESET);
  });

  it('returns customer preset for CUSTOMER role', () => {
    expect(resolvePersonaPreset(['CUSTOMER'])).toBe(CUSTOMER_PRESET);
  });

  it('returns null for ADMIN (no fixed persona)', () => {
    expect(resolvePersonaPreset(['ADMIN'])).toBeNull();
  });

  it('returns null for empty roles', () => {
    expect(resolvePersonaPreset([])).toBeNull();
  });

  it('returns null for non-array input', () => {
    expect(resolvePersonaPreset(null)).toBeNull();
  });

  it('prioritises TECHNICIAN over DISPATCHER in multi-role scenario', () => {
    expect(resolvePersonaPreset(['DISPATCHER', 'TECHNICIAN'])).toBe(TECHNICIAN_PRESET);
  });

  it('prioritises DISPATCHER over MANAGER', () => {
    expect(resolvePersonaPreset(['MANAGER', 'DISPATCHER'])).toBe(DISPATCHER_PRESET);
  });
});

describe('getPersonaPreset', () => {
  it('returns correct preset for each persona name', () => {
    expect(getPersonaPreset('technician')).toBe(TECHNICIAN_PRESET);
    expect(getPersonaPreset('dispatcher')).toBe(DISPATCHER_PRESET);
    expect(getPersonaPreset('manager')).toBe(MANAGER_PRESET);
    expect(getPersonaPreset('customer')).toBe(CUSTOMER_PRESET);
  });

  it('returns null for unknown persona', () => {
    expect(getPersonaPreset('unknown')).toBeNull();
  });
});

describe('PERSONA_PRESETS ordering (BR-35 field-first)', () => {
  it('has technician as the first key', () => {
    expect(PERSONA_NAMES[0]).toBe('technician');
  });

  it('contains all four persona keys', () => {
    expect(PERSONA_NAMES).toContain('technician');
    expect(PERSONA_NAMES).toContain('dispatcher');
    expect(PERSONA_NAMES).toContain('manager');
    expect(PERSONA_NAMES).toContain('customer');
  });

  it('technician has the highest bodyContrastMin', () => {
    const maxContrast = Math.max(
      ...Object.values(PERSONA_PRESETS).map((p) => p.bodyContrastMin),
    );
    expect(TECHNICIAN_PRESET.bodyContrastMin).toBe(maxContrast);
  });

  it('dispatcher has the smallest rowHeight (highest density)', () => {
    const minRow = Math.min(
      ...Object.values(PERSONA_PRESETS).map((p) => p.rowHeight),
    );
    expect(DISPATCHER_PRESET.rowHeight).toBe(minRow);
  });
});
