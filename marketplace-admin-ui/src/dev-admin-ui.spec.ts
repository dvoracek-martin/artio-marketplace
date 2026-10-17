import { describe, expect, it } from 'vitest';
import { versionedApiPath } from './api-contract';

describe('local Marketplace development console API contract', () => {
  it('uses only the versioned development API namespace', () => {
    expect(versionedApiPath('/api/v1/dev-admin/session')).toBe('/api/v1/dev-admin/session');
    expect(() => versionedApiPath('/api/dev-admin/session')).toThrow();
  });
  it('keeps browser-side release operations behind the dev-admin API', () => {
    expect(versionedApiPath('/api/v1/dev-admin/releases')).toBe('/api/v1/dev-admin/releases');
  });
});
