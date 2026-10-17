export function versionedApiPath(path: string): string {
  if (!/^\/api\/v1\/[a-z0-9/_{}.-]+(?:\?.*)?$/.test(path)) {
    throw new Error('Marketplace API endpoints must use /api/v1.');
  }
  return path;
}
