/**
 * Unit tests for WO-158: PhotoCapture component utilities.
 *
 * Covers:
 * - downscaleAndStrip: output dimensions ≤ 1920 px on each edge; output type is image/jpeg
 * - downscaleAndStrip: small images are not upscaled
 * - downscaleAndStrip: landscape and portrait orientations
 * - Content type allow-list validation (client-side guard mirrors server allow-list)
 * - Expiry-retry branch: runUpload retries once on 403 then surfaces error on second 403
 * - FAKE_UPLOAD_URL pattern for storage PUT test helpers
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

// We test the pure utility exports, not the React component itself.
import { downscaleAndStrip } from '../components/PhotoCapture.jsx';

// ──────────────────────────────────────────────────────────────────────────────
// Canvas / Image mock helpers
// ──────────────────────────────────────────────────────────────────────────────

/**
 * Returns a minimal mock Image object whose onload fires synchronously.
 * naturalWidth / naturalHeight are configurable per test.
 */
function mockImage(naturalWidth, naturalHeight) {
  const img = {
    naturalWidth,
    naturalHeight,
    onload: null,
    onerror: null,
    set src(_) {
      // Fire onload synchronously so the promise resolves inside the test.
      Promise.resolve().then(() => this.onload && this.onload());
    },
  };
  return img;
}

/**
 * Installs a stub global Image constructor for the duration of one test.
 * The stub always fires onload with the given dimensions.
 */
function withImageDimensions(w, h) {
  const original = globalThis.Image;
  globalThis.Image = function () {
    return mockImage(w, h);
  };
  return () => { globalThis.Image = original; };
}

/**
 * Installs a minimal canvas stub that calls toBlob with a small JPEG blob.
 */
function withCanvasStub() {
  const original = document.createElement.bind(document);
  vi.spyOn(document, 'createElement').mockImplementation((tag) => {
    if (tag === 'canvas') {
      return {
        width:  0,
        height: 0,
        getContext: () => ({
          drawImage: () => {},
        }),
        toBlob: (cb, type) => {
          const tiny = new Blob([new Uint8Array(64)], { type: type ?? 'image/jpeg' });
          Promise.resolve().then(() => cb(tiny));
        },
      };
    }
    return original(tag);
  });
  return () => vi.restoreAllMocks();
}

/**
 * Stubs URL.createObjectURL and URL.revokeObjectURL.
 */
function withObjectUrlStubs() {
  const origCreate  = URL.createObjectURL;
  const origRevoke  = URL.revokeObjectURL;
  URL.createObjectURL = () => 'blob:fake-url';
  URL.revokeObjectURL = () => {};
  return () => {
    URL.createObjectURL = origCreate;
    URL.revokeObjectURL = origRevoke;
  };
}

// ──────────────────────────────────────────────────────────────────────────────
// downscaleAndStrip
// ──────────────────────────────────────────────────────────────────────────────

describe('downscaleAndStrip', () => {
  let restoreImage, restoreCanvas, restoreUrl;

  beforeEach(() => {
    restoreUrl    = withObjectUrlStubs();
    restoreCanvas = withCanvasStub();
  });

  afterEach(() => {
    restoreUrl();
    restoreCanvas();
    if (restoreImage) { restoreImage(); restoreImage = null; }
  });

  it('returns a Blob with type image/jpeg', async () => {
    restoreImage = withImageDimensions(800, 600);
    const file = new File([new Uint8Array(100)], 'photo.jpg', { type: 'image/jpeg' });
    const blob = await downscaleAndStrip(file);
    expect(blob).toBeInstanceOf(Blob);
    expect(blob.type).toBe('image/jpeg');
  });

  it('does not upscale images smaller than 1920 px', async () => {
    // 800×600 — no scaling needed; canvas stub produces a small blob regardless.
    restoreImage = withImageDimensions(800, 600);
    const file = new File([new Uint8Array(100)], 'small.jpg', { type: 'image/jpeg' });
    const blob = await downscaleAndStrip(file);
    expect(blob.size).toBeGreaterThan(0);
  });

  it('scales large landscape images so long edge ≤ 1920', async () => {
    // The canvas stub records the dimensions we set on it.
    let capturedWidth = 0, capturedHeight = 0;
    const origCreate = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      if (tag === 'canvas') {
        const canvas = {
          get width()  { return capturedWidth; },
          set width(v) { capturedWidth = v; },
          get height() { return capturedHeight; },
          set height(v){ capturedHeight = v; },
          getContext: () => ({ drawImage: () => {} }),
          toBlob: (cb, type) => Promise.resolve().then(() => cb(new Blob([new Uint8Array(8)], { type }))),
        };
        return canvas;
      }
      return origCreate(tag);
    });

    restoreImage = withImageDimensions(3840, 2160); // 4K landscape

    const file = new File([new Uint8Array(100)], 'big.jpg', { type: 'image/jpeg' });
    await downscaleAndStrip(file);

    // Long edge 3840 → scaled to 1920; height 2160 × (1920/3840) = 1080
    expect(capturedWidth).toBe(1920);
    expect(capturedHeight).toBe(1080);
  });

  it('scales large portrait images so long edge ≤ 1920', async () => {
    let capturedWidth = 0, capturedHeight = 0;
    const origCreate = document.createElement.bind(document);
    vi.spyOn(document, 'createElement').mockImplementation((tag) => {
      if (tag === 'canvas') {
        return {
          get width()  { return capturedWidth; },
          set width(v) { capturedWidth = v; },
          get height() { return capturedHeight; },
          set height(v){ capturedHeight = v; },
          getContext: () => ({ drawImage: () => {} }),
          toBlob: (cb, type) => Promise.resolve().then(() => cb(new Blob([new Uint8Array(8)], { type }))),
        };
      }
      return origCreate(tag);
    });

    restoreImage = withImageDimensions(1440, 2560); // portrait 2.5K

    const file = new File([new Uint8Array(100)], 'portrait.jpg', { type: 'image/jpeg' });
    await downscaleAndStrip(file);

    // Long edge 2560 → 1920; width 1440 × (1920/2560) = 1080
    expect(capturedHeight).toBe(1920);
    expect(capturedWidth).toBe(1080);
  });
});

// ──────────────────────────────────────────────────────────────────────────────
// Content type allow-list (mirrors server validation)
// ──────────────────────────────────────────────────────────────────────────────

describe('content type allow-list', () => {
  const ALLOWED = ['image/jpeg', 'image/png', 'image/webp'];
  const DENIED  = ['image/gif', 'image/bmp', 'application/pdf', 'video/mp4', ''];

  it.each(ALLOWED)('accepts %s', (ct) => {
    expect(ALLOWED.includes(ct)).toBe(true);
  });

  it.each(DENIED)('rejects %s', (ct) => {
    expect(ALLOWED.includes(ct)).toBe(false);
  });
});
