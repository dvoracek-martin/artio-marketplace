export function versionedApiPath(path: string): string {
  if (!/^\/api\/v1\/[a-z0-9/_{}.-]+(?:\?.*)?$/.test(path)) {
    throw new Error('Marketplace API endpoints must use /api/v1.');
  }
  return path;
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
  if (role === 'MAIN') {
    manifest.image = publicReference;
  } else if (role.startsWith('SIDECAR:')) {
    const serviceId = role.slice('SIDECAR:'.length);
    const services = manifest.services;
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
