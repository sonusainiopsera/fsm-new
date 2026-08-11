import { describe, it, expect } from 'vitest';
import {
  parseHex,
  relativeLuminance,
  contrastRatio,
  assertContrast,
  validatePairings,
  validatePersonaPairings,
  assertGreyscaleSurvivability,
} from '../contrast.js';
import manifest from '../contrastPairings.json';

describe('parseHex', () => {
  it('parses a 6-digit hex colour', () => {
    expect(parseHex('#212529')).toEqual({ r: 33, g: 37, b: 41 });
  });

  it('parses a 3-digit hex colour', () => {
    expect(parseHex('#fff')).toEqual({ r: 255, g: 255, b: 255 });
  });

  it('parses without leading #', () => {
    expect(parseHex('ffffff')).toEqual({ r: 255, g: 255, b: 255 });
  });

  it('throws on invalid hex', () => {
    expect(() => parseHex('#xyz')).toThrow();
  });
});

describe('relativeLuminance', () => {
  it('returns 1.0 for white', () => {
    expect(relativeLuminance({ r: 255, g: 255, b: 255 })).toBeCloseTo(1.0, 3);
  });

  it('returns 0.0 for black', () => {
    expect(relativeLuminance({ r: 0, g: 0, b: 0 })).toBeCloseTo(0.0, 3);
  });

  it('returns a value in [0, 1]', () => {
    const l = relativeLuminance({ r: 134, g: 142, b: 150 });
    expect(l).toBeGreaterThan(0);
    expect(l).toBeLessThan(1);
  });
});

describe('contrastRatio', () => {
  it('returns 21:1 for black on white', () => {
    expect(contrastRatio('#000000', '#ffffff')).toBeCloseTo(21, 0);
  });

  it('returns 1:1 for identical colours', () => {
    expect(contrastRatio('#ffffff', '#ffffff')).toBeCloseTo(1, 1);
  });

  it('is symmetric (fg/bg order does not matter)', () => {
    const a = contrastRatio('#212529', '#ffffff');
    const b = contrastRatio('#ffffff', '#212529');
    expect(a).toBeCloseTo(b, 5);
  });

  it('primary text on white light surface passes 7:1', () => {
    // #212529 on #ffffff: empirically ~14:1
    expect(contrastRatio('#212529', '#ffffff')).toBeGreaterThanOrEqual(7.0);
  });

  it('primary text on dark surface passes 4.5:1', () => {
    // #f1f3f5 on #101214
    expect(contrastRatio('#f1f3f5', '#101214')).toBeGreaterThanOrEqual(4.5);
  });
});

describe('assertContrast', () => {
  it('returns pass=true when ratio meets minimum', () => {
    const result = assertContrast('#000000', '#ffffff', 4.5, 'test-pair');
    expect(result.pass).toBe(true);
    expect(result.pairingId).toBe('test-pair');
  });

  it('returns pass=false when ratio is below minimum', () => {
    const result = assertContrast('#aaaaaa', '#ffffff', 4.5);
    expect(result.pass).toBe(false);
  });

  it('includes ratio and required in the result', () => {
    const result = assertContrast('#000000', '#ffffff', 7.0);
    expect(result.ratio).toBeGreaterThanOrEqual(7.0);
    expect(result.required).toBe(7.0);
  });
});

describe('validatePairings — manifest', () => {
  it('all committed pairings pass in both appearances', () => {
    const { failures } = validatePairings(manifest);
    if (failures.length > 0) {
      const msg = failures.map((f) =>
        `${f.id} [${f.appearance}]: ${f.ratio}:1 < required ${f.required}:1 (fg=${f.fg} bg=${f.bg})`
      ).join('\n');
      expect.fail(`Contrast failures in contrastPairings.json:\n${msg}`);
    }
    expect(failures).toHaveLength(0);
  });
});

describe('validatePersonaPairings', () => {
  it('technician pairings pass 7:1 in light', () => {
    const { failures } = validatePersonaPairings(manifest, 'technician', 'light');
    expect(failures).toHaveLength(0);
  });

  it('technician pairings pass 4.5:1 in dark', () => {
    const technicianDarkPairings = {
      ...manifest,
      pairings: manifest.pairings
        .filter((p) => p.personas?.includes('technician') && p.minRatio >= 4.5),
    };
    const { failures } = validatePersonaPairings(technicianDarkPairings, 'technician', 'dark');
    expect(failures).toHaveLength(0);
  });

  it('dispatcher pairings pass 4.5:1 in light', () => {
    const { failures } = validatePersonaPairings(manifest, 'dispatcher', 'light');
    expect(failures).toHaveLength(0);
  });

  it('dispatcher pairings pass 4.5:1 in dark', () => {
    const { failures } = validatePersonaPairings(manifest, 'dispatcher', 'dark');
    expect(failures).toHaveLength(0);
  });
});

describe('assertGreyscaleSurvivability', () => {
  it('passes for an element with text + aria-hidden icon', () => {
    const el = document.createElement('span');
    el.setAttribute('role', 'status');
    const icon = document.createElement('span');
    icon.setAttribute('aria-hidden', 'true');
    icon.setAttribute('data-shape', 'circle');
    icon.textContent = '●';
    el.appendChild(icon);
    const text = document.createElement('span');
    text.textContent = 'In Progress';
    el.appendChild(text);

    const result = assertGreyscaleSurvivability(el);
    expect(result.pass).toBe(true);
    expect(result.missingText).toBe(false);
    expect(result.missingIcon).toBe(false);
  });

  it('fails for an element with no icon', () => {
    const el = document.createElement('span');
    const text = document.createElement('span');
    text.textContent = 'In Progress';
    el.appendChild(text);

    const result = assertGreyscaleSurvivability(el);
    expect(result.pass).toBe(false);
    expect(result.missingIcon).toBe(true);
  });

  it('fails for an element with no text', () => {
    const el = document.createElement('span');
    const icon = document.createElement('span');
    icon.setAttribute('aria-hidden', 'true');
    icon.setAttribute('data-shape', 'circle');
    icon.textContent = '●';
    el.appendChild(icon);

    const result = assertGreyscaleSurvivability(el);
    expect(result.pass).toBe(false);
    expect(result.missingText).toBe(true);
  });
});
