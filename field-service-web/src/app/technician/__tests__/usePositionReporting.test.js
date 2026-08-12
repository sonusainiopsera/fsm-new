/**
 * Unit tests for WO-159: usePositionReporting hook.
 *
 * Covers:
 * - Hook does not call watchPosition when job is not in active state
 * - Hook calls watchPosition when state is EN_ROUTE
 * - Hook calls watchPosition when state is IN_PROGRESS
 * - Throttle: second position event within 60s is not sent
 * - Stops on unmount (clearWatch called)
 * - PERMISSION_DENIED: stops reporting, setSharing(false)
 * - POSITION_UNAVAILABLE: stops reporting silently
 * - Inactive state (COMPLETED): does not start watch
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import React, { useState } from 'react';
import { usePositionReporting } from '../hooks/usePositionReporting.js';
import { PositionSharingContext } from '../PositionSharingContext.js';

// ── Geolocation mock ──────────────────────────────────────────────────────────

let watchCallback = null;
let watchErrorCallback = null;
let watchId = 42;
const clearWatchMock = vi.fn();
const watchPositionMock = vi.fn((success, error) => {
  watchCallback = success;
  watchErrorCallback = error;
  return watchId;
});

const geoMock = {
  watchPosition: watchPositionMock,
  clearWatch: clearWatchMock,
};

// ── Fetch mock ────────────────────────────────────────────────────────────────

const fetchMock = vi.fn(() => Promise.resolve({ ok: true, status: 202 }));

// ── Wrapper ───────────────────────────────────────────────────────────────────

function Wrapper({ children }) {
  const [sharing, setSharing] = useState(false);
  return (
    <PositionSharingContext.Provider value={{ sharing, setSharing }}>
      {children}
    </PositionSharingContext.Provider>
  );
}

function renderPositionHook(jobState) {
  return renderHook(
    ({ state }) => usePositionReporting(state, 'test-job-id'),
    {
      initialProps: { state: jobState },
      wrapper: Wrapper,
    }
  );
}

// ── Setup / teardown ──────────────────────────────────────────────────────────

beforeEach(() => {
  watchCallback    = null;
  watchErrorCallback = null;
  watchPositionMock.mockClear();
  clearWatchMock.mockClear();
  fetchMock.mockClear();

  Object.defineProperty(globalThis, 'navigator', {
    value: { geolocation: geoMock },
    writable: true,
    configurable: true,
  });

  globalThis.fetch = fetchMock;

  // Reset visibility to visible
  Object.defineProperty(document, 'visibilityState', {
    value: 'visible',
    writable: true,
    configurable: true,
  });
});

afterEach(() => {
  vi.restoreAllMocks();
});

// ── Tests ─────────────────────────────────────────────────────────────────────

describe('usePositionReporting', () => {

  it('does not start watchPosition when state is ASSIGNED', () => {
    renderPositionHook('ASSIGNED');
    expect(watchPositionMock).not.toHaveBeenCalled();
  });

  it('does not start watchPosition when state is COMPLETED', () => {
    renderPositionHook('COMPLETED');
    expect(watchPositionMock).not.toHaveBeenCalled();
  });

  it('does not start watchPosition when state is null', () => {
    renderPositionHook(null);
    expect(watchPositionMock).not.toHaveBeenCalled();
  });

  it('starts watchPosition when state is EN_ROUTE', () => {
    renderPositionHook('EN_ROUTE');
    expect(watchPositionMock).toHaveBeenCalledOnce();
  });

  it('starts watchPosition when state is IN_PROGRESS', () => {
    renderPositionHook('IN_PROGRESS');
    expect(watchPositionMock).toHaveBeenCalledOnce();
  });

  it('sends a position report when a position is received', async () => {
    renderPositionHook('EN_ROUTE');

    await act(async () => {
      watchCallback({
        coords: { latitude: 51.5, longitude: -0.1, accuracy: 20 },
        timestamp: Date.now(),
      });
    });

    expect(fetchMock).toHaveBeenCalledOnce();
    const [url, opts] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/v1/technicians/me/position');
    expect(opts.method).toBe('POST');
    const body = JSON.parse(opts.body);
    expect(body).toMatchObject({
      latitude: 51.5,
      longitude: -0.1,
      accuracyMetres: 20,
    });
  });

  it('throttles: second position within 60s is not sent', async () => {
    vi.useFakeTimers();
    renderPositionHook('EN_ROUTE');

    const pos = { coords: { latitude: 51.5, longitude: -0.1, accuracy: 20 }, timestamp: Date.now() };

    await act(async () => { watchCallback(pos); });
    expect(fetchMock).toHaveBeenCalledOnce();

    // Second call within 60s — should be throttled
    await act(async () => { watchCallback(pos); });
    expect(fetchMock).toHaveBeenCalledOnce();

    // Advance past throttle window
    act(() => { vi.advanceTimersByTime(61_000); });

    await act(async () => { watchCallback(pos); });
    expect(fetchMock).toHaveBeenCalledTimes(2);

    vi.useRealTimers();
  });

  it('calls clearWatch on unmount', () => {
    const { unmount } = renderPositionHook('EN_ROUTE');
    unmount();
    expect(clearWatchMock).toHaveBeenCalledWith(watchId);
  });

  it('stops on PERMISSION_DENIED geolocation error', () => {
    renderPositionHook('EN_ROUTE');
    expect(watchPositionMock).toHaveBeenCalledOnce();

    act(() => {
      watchErrorCallback({ code: 1 }); // PERMISSION_DENIED
    });

    expect(clearWatchMock).toHaveBeenCalled();
  });

  it('stops on POSITION_UNAVAILABLE geolocation error', () => {
    renderPositionHook('EN_ROUTE');
    act(() => {
      watchErrorCallback({ code: 2 }); // POSITION_UNAVAILABLE
    });
    expect(clearWatchMock).toHaveBeenCalled();
  });

  it('stops when state transitions from EN_ROUTE to COMPLETED', () => {
    const { rerender } = renderPositionHook('EN_ROUTE');
    expect(watchPositionMock).toHaveBeenCalledOnce();

    rerender({ state: 'COMPLETED' });
    expect(clearWatchMock).toHaveBeenCalled();
  });

  it('does not start if geolocation is unavailable', () => {
    Object.defineProperty(globalThis, 'navigator', {
      value: {},
      writable: true,
      configurable: true,
    });
    renderPositionHook('EN_ROUTE');
    expect(watchPositionMock).not.toHaveBeenCalled();
  });
});
