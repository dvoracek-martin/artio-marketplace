import { describe, expect, it } from 'vitest';
import { applyImportedDigestToManifest, versionedApiPath } from './api-contract';

describe('local Marketplace development console API contract', () => {
  it('uses only the versioned development API namespace', () => {
    expect(versionedApiPath('/api/v1/dev-admin/session')).toBe('/api/v1/dev-admin/session');
    expect(() => versionedApiPath('/api/dev-admin/session')).toThrow();
  });
  it('keeps browser-side release operations behind the dev-admin API', () => {
    expect(versionedApiPath('/api/v1/dev-admin/releases')).toBe('/api/v1/dev-admin/releases');
  });

  it('applies the verified MAIN digest without changing other manifest fields', () => {
    const source = JSON.stringify({ id: 'example.plugin', image: 'old', services: [] });
    const applied = JSON.parse(
      applyImportedDigestToManifest(source, 'MAIN', 'registry.example/plugin@sha256:abc'),
    );
    expect(applied).toEqual({
      id: 'example.plugin',
      image: 'registry.example/plugin@sha256:abc',
      services: [],
    });
  });

  it('applies a SIDECAR digest only to the matching existing Core service', () => {
    const source = JSON.stringify({
      image: 'main@sha256:main',
      services: [
        { id: 'worker', image: 'old-worker', port: 8080 },
        { id: 'metrics', image: 'metrics@sha256:metrics', port: 9090 },
      ],
    });
    const applied = JSON.parse(
      applyImportedDigestToManifest(source, 'SIDECAR:worker', 'registry.example/worker@sha256:abc'),
    );
    expect(applied.image).toBe('main@sha256:main');
    expect(applied.services).toEqual([
      { id: 'worker', image: 'registry.example/worker@sha256:abc', port: 8080 },
      { id: 'metrics', image: 'metrics@sha256:metrics', port: 9090 },
    ]);
  });

  it('refuses to invent a Core BundleService for a sidecar import', () => {
    expect(() =>
      applyImportedDigestToManifest('{"services":[]}', 'SIDECAR:worker', 'registry/image@sha256:abc'),
    ).toThrow("BundleService 'worker'");
  });
});
