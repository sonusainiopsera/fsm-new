/**
 * Mock transport layer for catalogue and integration tests.
 * Simulates network latency, HTTP error codes, and stale-data degradation
 * without any backend dependency.
 */

import dispatcherFixture from './fixtures/dispatcher.json';
import technicianFixture from './fixtures/technician.json';
import managerFixture from './fixtures/manager.json';
import customerFixture from './fixtures/customer.json';

/** @type {Record<string, unknown>} */
const FIXTURES = {
  dispatcher: dispatcherFixture,
  technician: technicianFixture,
  manager: managerFixture,
  customer: customerFixture,
};

/**
 * @typedef {{ latencyMs?: number, errorCode?: number | null, stale?: boolean }} MockTransportConfig
 */

/**
 * @typedef {{ data: unknown, stale: boolean, status: number }} MockResponse
 */

/**
 * Creates a mock transport with configurable behaviour.
 *
 * @param {MockTransportConfig} config
 * @returns {{ fetch: (persona: string) => Promise<MockResponse>, configure: (c: MockTransportConfig) => void }}
 */
export function createMockTransport(config = {}) {
  let cfg = {
    latencyMs: 400,
    errorCode: null,
    stale: false,
    ...config,
  };

  return {
    configure(newConfig) {
      cfg = { ...cfg, ...newConfig };
    },

    /**
     * Simulates fetching data for a persona.
     * @param {string} persona
     * @returns {Promise<MockResponse>}
     */
    fetch(persona) {
      return new Promise((resolve, reject) => {
        setTimeout(() => {
          if (cfg.errorCode === 403) {
            reject({ status: 403, code: 'FORBIDDEN', message: 'Access denied' });
            return;
          }
          if (cfg.errorCode === 409 || cfg.errorCode === 422) {
            reject({ status: cfg.errorCode, code: 'VALIDATION_ERROR', message: 'Request could not be processed', fieldErrors: [] });
            return;
          }
          if (cfg.errorCode != null) {
            reject({ status: cfg.errorCode, code: 'UNEXPECTED_ERROR', message: 'An unexpected error occurred' });
            return;
          }

          const data = FIXTURES[persona];
          if (!data) {
            reject({ status: 404, code: 'NOT_FOUND', message: `No fixture for persona: ${persona}` });
            return;
          }

          resolve({ data, stale: cfg.stale, status: 200 });
        }, cfg.latencyMs);
      });
    },
  };
}

/** Default transport used by the catalogue */
export const defaultMockTransport = createMockTransport({ latencyMs: 200 });
