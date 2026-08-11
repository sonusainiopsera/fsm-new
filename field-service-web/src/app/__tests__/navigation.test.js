import { describe, it, expect, vi } from 'vitest';
import { filterNavItems, defaultPathForRoles, ROLES } from '../navigation.js';

import adminToken from '../../mocks/fixtures/tokens/admin.json';
import dispatcherToken from '../../mocks/fixtures/tokens/dispatcher.json';
import technicianToken from '../../mocks/fixtures/tokens/technician.json';
import managerToken from '../../mocks/fixtures/tokens/manager.json';
import customerToken from '../../mocks/fixtures/tokens/customer.json';
import navExpectations from '../../mocks/fixtures/navExpectations.json';

function paths(items) {
  return items.map((i) => i.path);
}

describe('filterNavItems — role-based navigation (usability filter, not security control)', () => {
  it('ADMIN sees dispatch and operations surfaces', () => {
    const items = filterNavItems(adminToken.roles);
    expect(paths(items)).toEqual(navExpectations.ADMIN);
  });

  it('DISPATCHER sees only dispatch surface', () => {
    const items = filterNavItems(dispatcherToken.roles);
    expect(paths(items)).toEqual(navExpectations.DISPATCHER);
  });

  it('TECHNICIAN sees only field surface — no dispatcher-only entries', () => {
    const items = filterNavItems(technicianToken.roles);
    expect(paths(items)).toEqual(navExpectations.TECHNICIAN);
    const dispatchPaths = items.filter((i) => i.allowedRoles.includes(ROLES.DISPATCHER));
    expect(dispatchPaths).toHaveLength(0);
  });

  it('MANAGER sees dispatch and operations surfaces — no technician-only entries', () => {
    const items = filterNavItems(managerToken.roles);
    expect(paths(items)).toEqual(navExpectations.MANAGER);
    const fieldPaths = items.filter((i) => i.allowedRoles.includes(ROLES.TECHNICIAN));
    expect(fieldPaths).toHaveLength(0);
  });

  it('CUSTOMER sees only portal surface', () => {
    const items = filterNavItems(customerToken.roles);
    expect(paths(items)).toEqual(navExpectations.CUSTOMER);
  });

  it('multi-role token (DISPATCHER + MANAGER) produces union without duplicates', () => {
    const items = filterNavItems([ROLES.DISPATCHER, ROLES.MANAGER]);
    expect(paths(items)).toEqual(navExpectations.DISPATCHER_AND_MANAGER);
    const uniquePaths = new Set(paths(items));
    expect(uniquePaths.size).toBe(paths(items).length);
  });

  it('empty roles array returns empty navigation', () => {
    const items = filterNavItems([]);
    expect(items).toHaveLength(0);
  });

  it('unknown role emits a console warning and returns empty navigation', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const items = filterNavItems(['SUPER_ADMIN_TURBO']);
    expect(warn).toHaveBeenCalledWith(
      expect.stringContaining('Unrecognised role')
    );
    expect(paths(items)).toEqual(navExpectations.UNKNOWN_ROLE);
    warn.mockRestore();
  });

  it('known role mixed with unknown role still renders known entries and warns', () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const items = filterNavItems([ROLES.DISPATCHER, 'UNKNOWN']);
    expect(paths(items)).toContain('/dispatch');
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});

describe('defaultPathForRoles', () => {
  it('returns first nav item path for technician', () => {
    expect(defaultPathForRoles([ROLES.TECHNICIAN])).toBe('/field');
  });

  it('returns sign-in for empty roles', () => {
    expect(defaultPathForRoles([])).toBe('/sign-in');
  });

  it('returns first item (dispatch) for dispatcher', () => {
    expect(defaultPathForRoles([ROLES.DISPATCHER])).toBe('/dispatch');
  });
});
