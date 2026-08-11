import { describe, it, expect, vi, beforeEach } from 'vitest';
import { handleSseEvent, getInvalidationKeys, EVENT_KEY_MAP } from '../eventKeyMap.js';
import { queryClient } from '../queryClient.js';

describe('EVENT_KEY_MAP', () => {
  it('maps at-risk to work-orders query keys', () => {
    const keys = EVENT_KEY_MAP['at-risk'];
    expect(keys).toBeDefined();
    expect(keys.some(k => JSON.stringify(k).includes('work-orders'))).toBe(true);
  });

  it('maps breach to work-orders query keys', () => {
    const keys = EVENT_KEY_MAP['breach'];
    expect(keys).toBeDefined();
    expect(keys.length).toBeGreaterThan(0);
  });

  it('maps state-change to work-orders list key', () => {
    const keys = EVENT_KEY_MAP['state-change'];
    expect(keys).toBeDefined();
  });
});

describe('handleSseEvent', () => {
  beforeEach(() => {
    vi.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('invalidates mapped query keys on at-risk event', () => {
    handleSseEvent({ type: 'at-risk', data: '' });
    expect(queryClient.invalidateQueries).toHaveBeenCalled();
    const calledKeys = queryClient.invalidateQueries.mock.calls.map(c => c[0].queryKey);
    expect(calledKeys.some(k => k.includes('work-orders'))).toBe(true);
  });

  it('invalidates on breach event', () => {
    handleSseEvent({ type: 'breach', data: '' });
    expect(queryClient.invalidateQueries).toHaveBeenCalled();
  });

  it('does not throw for unknown event types', () => {
    expect(() => handleSseEvent({ type: 'unknown-event', data: '' })).not.toThrow();
    expect(queryClient.invalidateQueries).not.toHaveBeenCalled();
  });

  it('parses eventType from JSON data in message events', () => {
    handleSseEvent({ type: 'message', data: JSON.stringify({ eventType: 'at-risk', id: 'wo-1' }) });
    expect(queryClient.invalidateQueries).toHaveBeenCalled();
  });
});

describe('getInvalidationKeys', () => {
  it('returns keys for known event types', () => {
    const keys = getInvalidationKeys('at-risk');
    expect(Array.isArray(keys)).toBe(true);
    expect(keys.length).toBeGreaterThan(0);
  });

  it('returns undefined for unknown event types', () => {
    expect(getInvalidationKeys('nonexistent')).toBeUndefined();
  });
});
