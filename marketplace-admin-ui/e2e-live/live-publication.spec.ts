import { expect, test } from '@playwright/test';

const pluginId = process.env.MARKETPLACE_LIVE_PLUGIN_ID;
const version = process.env.MARKETPLACE_LIVE_VERSION ?? '1.0.0';
const archivePath = process.env.MARKETPLACE_LIVE_ARCHIVE_PATH;
const archiveTag = process.env.MARKETPLACE_LIVE_ARCHIVE_TAG;
const registryBase = process.env.MARKETPLACE_LIVE_REGISTRY_BASE;
const png = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDvkAAAAASUVORK5CYII=',
  'base64',
);

test('publishes a real staged OCI image through the live development browser UI', async ({ page, request, baseURL }) => {
  expect(pluginId, 'MARKETPLACE_LIVE_PLUGIN_ID').toBeTruthy();
  expect(archivePath, 'MARKETPLACE_LIVE_ARCHIVE_PATH').toBeTruthy();
  expect(archiveTag, 'MARKETPLACE_LIVE_ARCHIVE_TAG').toBeTruthy();
  expect(registryBase, 'MARKETPLACE_LIVE_REGISTRY_BASE').toBeTruthy();
  if (!pluginId || !archivePath || !archiveTag || !registryBase || !baseURL) return;

  const browserErrors: string[] = [];
  page.on('pageerror', (error) => browserErrors.push(error.message));
  await page.goto('/dev-admin/');
  await expect(page.getByRole('heading', { name: 'Marketplace control room' })).toBeVisible();
  await page.getByRole('button', { name: '+ Nový release' }).click();

  const manifest = {
    packageManifest: {
      manifestVersion: '2',
      id: pluginId,
      name: 'Live browser publication acceptance',
      vendor: 'Artio Marketplace',
      version,
      description: 'Publishes a real imported archive through the development console.',
      minimumCoreVersion: '0.1.0',
      maximumCoreVersion: '99.0.0',
      image: 'localhost:15000/staging/pending@sha256:' + '0'.repeat(64),
      dependencies: [],
      permissions: [],
      configuration: {},
      extensionPoints: [],
      healthPath: '/health',
      diagnosticsPath: '/diagnostics',
      geographicAvailability: { scope: 'GLOBAL', countries: [] },
      persistentPaths: [],
      services: [],
    },
    capabilities: [{ capabilityId: 'payment.provider.v1', operations: ['createCheckout'], permissions: ['payment.process'], accountFingerprints: {} }],
    subscriptions: [{ eventType: 'order.paid', schemaVersion: 1, permissions: [] }],
  };
  await page.getByLabel('Runtime manifest JSON').fill(JSON.stringify(manifest, null, 2));

  await page.getByLabel('Plugin ID').fill(pluginId);
  await page.getByLabel('Verze').fill(version);
  await page.getByLabel('Archive image tag').fill(archiveTag);
  // Pass the large archive by path: Playwright limits in-memory FilePayloads
  // to 50 MiB, while the real streamed acceptance fixture is larger.
  await page.getByLabel('Docker save / OCI archive').setInputFiles(archivePath);
  await page.getByRole('button', { name: 'Nahrát a importovat' }).click();
  const uploadProgress = page.locator('.progress progress');
  await expect(uploadProgress).toBeVisible({ timeout: 10_000 });
  // Fast local/hosted runners can finish the upload before Playwright samples
  // an intermediate byte-count event. Assert the browser's real upload
  // progress reached its completed byte count instead of requiring a transient
  // "MiB" status to remain visible after XHR completion.
  await expect(uploadProgress).toHaveJSProperty('value', 100, { timeout: 30_000 });
  const publicReference = page.locator('.import-result code').last();
  await expect(publicReference).toContainText('@sha256:', { timeout: 240_000 });
  const imageReference = (await publicReference.textContent())?.trim() ?? '';
  expect(imageReference).toMatch(/^localhost:\d+\/public\/plugins\//);
  await page.getByRole('button', { name: 'Použít digest v runtime manifestu' }).click();
  await expect.poll(() => page.getByLabel('Runtime manifest JSON').inputValue()).toContain(imageReference);

  const catalog = {
    schemaVersion: 1,
    pluginId,
    slug: `live-browser-${pluginId.split('.').at(-1)}`,
    title: { en: 'Live browser acceptance' },
    tagline: { en: 'Published in a real browser' },
    description: { en: 'Exercises the live local Marketplace console.' },
    releaseNotes: { en: 'Live browser publication acceptance.' },
    vendorDisplayName: 'Artio Marketplace',
    author: 'Marketplace acceptance',
    categories: ['other'],
    tags: ['live-browser', 'acceptance'],
    pricingModel: 'FREE',
    icon: 'sha256-' + '0'.repeat(64),
    screenshots: [{
      assetId: 'sha256-' + '0'.repeat(64),
      order: 0,
      alt: { en: 'Live browser acceptance image' },
      caption: { en: 'Created by the live browser test' },
    }],
  };
  await page.getByLabel('Catalog metadata JSON').fill(JSON.stringify(catalog, null, 2));
  await page.getByLabel('Ikona katalogu').setInputFiles({ name: 'icon.png', mimeType: 'image/png', buffer: png });
  await page.getByLabel('Screenshots ve stejném pořadí jako metadata.screenshots').setInputFiles({
    name: 'screenshot.png',
    mimeType: 'image/png',
    buffer: png,
  });
  await page.getByLabel('Akce').selectOption('PUBLISH_NOW');
  await page.getByRole('button', { name: 'Validovat' }).click();
  await expect(page.getByText('Manifest a OCI artifact jsou validní')).toBeVisible({ timeout: 60_000 });
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Uložit, schválit a publikovat' }).click();
  await expect(page.getByText(`Publikováno a podepsáno: ${pluginId}@${version}`)).toBeVisible({ timeout: 120_000 });

  const descriptorResponse = await request.get(
    new URL(`/api/v1/marketplace/plugins/${encodeURIComponent(pluginId)}/releases/${version}/descriptor`, baseURL).toString(),
  );
  expect(descriptorResponse.status()).toBe(200);
  const envelope = await descriptorResponse.json();
  expect(envelope.signature.algorithm).toBe('Ed25519');
  expect(envelope.signature.keyId).toBeTruthy();
  expect(envelope.signature.value).toBeTruthy();
  expect(envelope.descriptor.pluginId).toBe(pluginId);
  expect(envelope.descriptor.manifest.packageManifest.image).toBe(imageReference);
  const coreManifestResponse = await request.get(
    new URL(`/api/v1/marketplace/plugins/${encodeURIComponent(pluginId)}/releases/${version}/manifest`, baseURL).toString(),
  );
  expect(coreManifestResponse.status()).toBe(200);
  expect((await coreManifestResponse.json()).packageManifest.image).toBe(imageReference);
  await expect(page.getByRole('button', { name: new RegExp(pluginId.replaceAll('.', '\\.')) })).toBeVisible();

  const registryPath = imageReference.replace(/^localhost:\d+/, '');
  const digest = imageReference.slice(imageReference.lastIndexOf('@') + 1);
  const manifestPath = registryPath.slice(0, registryPath.lastIndexOf('@'));
  const registryResponse = await request.head(`${registryBase}/v2${manifestPath}/manifests/${digest}`, {
    headers: { Accept: 'application/vnd.oci.image.index.v1+json, application/vnd.oci.image.manifest.v1+json' },
  });
  expect(registryResponse.status()).toBe(200);
  expect(registryResponse.headers()['docker-content-digest']).toBe(digest);
  expect(browserErrors).toEqual([]);
});
