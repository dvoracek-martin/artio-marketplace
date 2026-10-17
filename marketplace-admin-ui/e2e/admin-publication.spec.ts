import { expect, test } from '@playwright/test';

test('uploads an OCI archive and completes the signed publication flow through the admin UI', async ({ page }) => {
  const calls: Array<{ method: string; path: string; body: string }> = [];
  let assetNumber = 0;
  const jobId = 'a61e9307-006f-4c61-a7aa-e2b7b405f74f';
  const releaseId = '6bd5040d-35ac-4549-97e7-f558c86bc2cc';
  const digestReference = 'localhost:15000/public/plugins/com.example.browser/1.0.0/main@sha256:' + 'a'.repeat(64);
  const browserErrors: string[] = [];

  await page.route('**/api/v1/**', async (route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname;
    const method = request.method();
    const body = request.postData() ?? '';
    calls.push({ method, path, body });

    if (path === '/api/v1/dev-admin/session' && method === 'GET') {
      await route.fulfill({ json: { apiVersion: 'v1', csrfToken: 'browser-test-csrf-token' } });
    } else if (path === '/api/v1/dev-admin/plugins' && method === 'GET') {
      await route.fulfill({ json: [] });
    } else if (path === '/api/v1/dev-admin/operations' && method === 'GET') {
      await route.fulfill({ json: { api: 'UP', database: 'UP', zot: 'UP', environment: 'browser-test', apiBase: '/api/v1' } });
    } else if (path === '/api/v1/dev-admin/imports' && method === 'POST') {
      await route.fulfill({ json: { jobId, state: 'QUEUED' }, status: 202 });
    } else if (path === `/api/v1/dev-admin/imports/${jobId}/archive` && method === 'PUT') {
      await route.fulfill({ json: { jobId, state: 'STAGED' }, status: 202 });
    } else if (path === `/api/v1/dev-admin/imports/${jobId}` && method === 'GET') {
      await route.fulfill({
        json: {
          jobId,
          state: 'STAGED',
          role: 'MAIN',
          image: digestReference,
          publicReference: digestReference,
          stagingReference: 'localhost:15000/staging/plugins/com.example.browser/1.0.0/main@sha256:' + 'a'.repeat(64),
        },
      });
    } else if (path === '/api/v1/dev-admin/releases/validate' && method === 'POST') {
      await route.fulfill({ json: { valid: true, errors: [], warnings: [] } });
    } else if (path === '/api/v1/dev-admin/releases' && method === 'POST') {
      await route.fulfill({ json: { releaseId, state: 'VALIDATED', approvalState: 'PENDING' }, status: 201 });
    } else if (path === `/api/v1/dev-admin/releases/${releaseId}/assets` && method === 'POST') {
      assetNumber += 1;
      await route.fulfill({ json: { assetId: `sha256-${String(assetNumber).padStart(64, '0')}` }, status: 201 });
    } else if (path === `/api/v1/dev-admin/releases/${releaseId}/catalog` && method === 'PUT') {
      await route.fulfill({ json: { releaseId, state: 'VALIDATED' } });
    } else if (path === `/api/v1/dev-admin/releases/${releaseId}/approve` && method === 'POST') {
      await route.fulfill({ json: { releaseId, approvalState: 'APPROVED' } });
    } else if (path === `/api/v1/dev-admin/releases/${releaseId}/publish` && method === 'POST') {
      await route.fulfill({ json: { releaseId, state: 'PUBLISHED', signatureState: 'SIGNED' } });
    } else {
      await route.fulfill({ status: 404, json: { error: `Unexpected browser-test request ${method} ${path}` } });
    }
  });

  page.on('pageerror', (error) => browserErrors.push(error.message));
  await page.goto('/dev-admin/');
  await expect(page.getByRole('heading', { name: 'Marketplace control room' })).toBeVisible();

  await page.getByLabel('Plugin ID').fill('com.example.browser');
  await page.getByLabel('Verze').fill('1.0.0');
  await page.getByLabel('Archive image tag').fill('fixture:1.0.0');
  await page.getByLabel('Docker save / OCI archive').setInputFiles({
    name: 'fixture.tar',
    mimeType: 'application/x-tar',
    buffer: Buffer.from('synthetic archive fixture'),
  });
  await page.getByRole('button', { name: 'Nahrát a importovat' }).click();
  await expect(page.getByText(digestReference)).toBeVisible({ timeout: 10_000 });
  await page.getByRole('button', { name: 'Použít digest v runtime manifestu' }).click();

  const manifest = {
    manifestVersion: '1',
    id: 'com.example.browser',
    name: 'Browser acceptance plugin',
    vendor: 'Artio Marketplace',
    version: '1.0.0',
    description: 'Exercises the development UI in a real browser.',
    minimumCoreVersion: '0.1.0',
    maximumCoreVersion: '99.0.0',
    image: digestReference,
    dependencies: [],
    permissions: ['payment.process'],
    configuration: {},
    extensionPoints: ['payment.provider.v1'],
    healthPath: '/health',
    diagnosticsPath: '/diagnostics',
    geographicAvailability: { scope: 'GLOBAL', countries: [] },
    persistentPaths: [],
    services: [],
  };
  const catalog = {
    schemaVersion: 1,
    pluginId: 'com.example.browser',
    slug: 'browser-acceptance',
    title: { en: 'Browser acceptance' },
    tagline: { en: 'UI flow test' },
    description: { en: 'A browser-driven Marketplace console test.' },
    releaseNotes: { en: 'First acceptance release.' },
    vendorDisplayName: 'Artio Marketplace',
    author: 'Artio Marketplace engineering',
    categories: ['other'],
    tags: ['acceptance'],
    pricingModel: 'FREE',
    icon: 'sha256-' + '0'.repeat(64),
    screenshots: [{ assetId: 'sha256-' + '0'.repeat(64), order: 0, alt: { en: 'Acceptance fixture' }, caption: { en: 'Browser test' } }],
  };

  await page.getByLabel('Runtime manifest JSON').fill(JSON.stringify(manifest, null, 2));
  await page.getByLabel('Catalog metadata JSON').fill(JSON.stringify(catalog, null, 2));
  await page.getByLabel('Ikona katalogu').setInputFiles({ name: 'icon.png', mimeType: 'image/png', buffer: Buffer.from('png fixture') });
  await page.getByLabel('Screenshots ve stejném pořadí jako metadata.screenshots').setInputFiles({
    name: 'screenshot.png',
    mimeType: 'image/png',
    buffer: Buffer.from('png fixture'),
  });
  await page.getByLabel('Akce').selectOption('PUBLISH_NOW');
  await page.getByRole('button', { name: 'Validovat' }).click();
  await expect(page.getByText('Manifest a OCI artifact jsou validní')).toBeVisible();
  page.on('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Uložit, schválit a publikovat' }).click();

  await expect(page.getByText(/Publikováno a podepsáno: com\.example\.browser@1\.0\.0/)).toBeVisible();
  expect(calls.map(({ method, path }) => `${method} ${path}`)).toEqual(expect.arrayContaining([
    'POST /api/v1/dev-admin/imports',
    `PUT /api/v1/dev-admin/imports/${jobId}/archive`,
    'POST /api/v1/dev-admin/releases/validate',
    'POST /api/v1/dev-admin/releases',
    `POST /api/v1/dev-admin/releases/${releaseId}/approve`,
    `POST /api/v1/dev-admin/releases/${releaseId}/publish`,
  ]));
  const submittedManifest = JSON.parse(calls.find((call) => call.path === '/api/v1/dev-admin/releases' && call.method === 'POST')!.body);
  expect(submittedManifest.manifest.image).toBe(digestReference);
  expect(browserErrors).toEqual([]);
});
