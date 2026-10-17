export function versionedApiPath(path: string): string {
  if (!/^\/api\/v1\/[a-z0-9/_{}.-]+(?:\?.*)?$/.test(path)) {
    throw new Error('Marketplace API endpoints must use /api/v1.');
  }
  return path;
}

export function coreManifestIdentity(value: unknown): { id: string; version: string } {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('Runtime manifest must be a JSON object.');
  }
  const manifest = value as Record<string, unknown>;
  const runtimeManifest = 'packageManifest' in manifest ? manifest.packageManifest : manifest;
  if (!runtimeManifest || typeof runtimeManifest !== 'object' || Array.isArray(runtimeManifest)) {
    throw new Error('Core v2 packageManifest must be a JSON object.');
  }
  const runtime = runtimeManifest as Record<string, unknown>;
  if (typeof runtime.id !== 'string' || typeof runtime.version !== 'string') {
    throw new Error('Core manifest must include its plugin id and version.');
  }
  return { id: runtime.id, version: runtime.version };
}

export function applyImportedDigestToManifest(
  manifestText: string,
  role: string,
  publicReference: string,
): string {
  const manifest: Record<string, unknown> = JSON.parse(manifestText);
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) {
    throw new Error('Runtime manifest must be a JSON object.');
  }
  let runtimeManifest = manifest;
  if ('packageManifest' in manifest) {
    const packageManifest = manifest.packageManifest;
    if (!packageManifest || typeof packageManifest !== 'object' || Array.isArray(packageManifest)) {
      throw new Error('Core v2 packageManifest must be a JSON object.');
    }
    runtimeManifest = packageManifest as Record<string, unknown>;
  }
  if (role === 'MAIN') {
    runtimeManifest.image = publicReference;
  } else if (role.startsWith('SIDECAR:')) {
    const serviceId = role.slice('SIDECAR:'.length);
    const services = runtimeManifest.services;
    const service = Array.isArray(services)
      ? services.find((item): item is Record<string, unknown> =>
          item !== null && typeof item === 'object' && !Array.isArray(item) && item.id === serviceId,
        )
      : undefined;
    if (!service) {
      throw new Error(
        `Runtime manifest does not contain BundleService '${serviceId}'. Add the service using the Core contract, then apply the digest again.`,
      );
    }
    service.image = publicReference;
  } else {
    throw new Error('Import has no supported MAIN/SIDECAR role.');
  }
  return JSON.stringify(manifest, null, 2);
}
