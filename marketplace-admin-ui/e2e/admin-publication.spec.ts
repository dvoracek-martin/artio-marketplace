import { expect, test } from '@playwright/test';

test('drives archive selection, import and publication controls through the admin UI', async ({ page }) => {
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
    } else if (path === '/api/v1/dev-admin/plugins/page' && method === 'GET') {
      await route.fulfill({ json: { content: [], page: 0, size: 50, totalElements: 0, totalPages: 0 } });
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

test('searches and filters catalog entries, then browses release states', async ({ page }) => {
  const pluginId = 'com.example.analytics';
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname;
    const method = route.request().method();
    if (path === '/api/v1/dev-admin/session' && method === 'GET') {
      await route.fulfill({ json: { apiVersion: 'v1', csrfToken: 'browser-test-csrf-token' } });
    } else if (path === '/api/v1/dev-admin/plugins/page' && method === 'GET') {
      const fixturePlugins = [
        { pluginId, slug: 'analytics', vendor: 'Example', moderationState: 'ACTIVE', releaseCount: 2, newestPublished: '2.0.0', listingRevisionVersion: 1, categories: ['analytics'] },
        { pluginId: 'com.example.security', slug: 'security', vendor: 'Example', moderationState: 'SUSPENDED', releaseCount: 1, newestPublished: '1.0.0', listingRevisionVersion: 1, categories: ['security'] },
        ...Array.from({ length: 50 }, (_, index) => ({ pluginId: `com.example.other-${index}`, slug: `other-${index}`, vendor: 'Fixture', moderationState: 'ACTIVE', releaseCount: 1, newestPublished: '1.0.0', listingRevisionVersion: 1, categories: ['other'] })),
      ];
      const query = new URL(route.request().url()).searchParams;
      const search = (query.get('q') ?? '').toLowerCase();
      const category = query.get('category') ?? 'ALL';
      const moderation = query.get('moderation') ?? 'ALL';
      const matchingPlugins = fixturePlugins.filter((plugin) =>
        (!search || `${plugin.pluginId} ${plugin.slug} ${plugin.vendor}`.toLowerCase().includes(search))
        && (category === 'ALL' || plugin.categories.includes(category))
        && (moderation === 'ALL' || plugin.moderationState === moderation));
      const page = Number(query.get('page') ?? '0');
      const size = Number(query.get('size') ?? '50');
      await route.fulfill({ json: {
        content: matchingPlugins.slice(page * size, (page + 1) * size), page, size,
        totalElements: matchingPlugins.length, totalPages: Math.ceil(matchingPlugins.length / size),
      } });
    } else if (path === '/api/v1/dev-admin/operations' && method === 'GET') {
      await route.fulfill({ json: { api: 'UP', database: 'UP', zot: 'UP', environment: 'browser-test', apiBase: '/api/v1' } });
    } else if (path === `/api/v1/dev-admin/plugins/${pluginId}/releases` && method === 'GET') {
      await route.fulfill({ json: [
        { releaseId: 'r2', version: '2.0.0', state: 'PUBLISHED', approvalState: 'APPROVED', manifestSha256: 'sha256:two', signed: true },
        { releaseId: 'r1', version: '1.0.0', state: 'UNLISTED', approvalState: 'APPROVED', manifestSha256: 'sha256:one', signed: true },
      ] });
    } else if (path === `/api/v1/dev-admin/plugins/${pluginId}/effective-listing` && method === 'GET') {
      await route.fulfill({ json: { metadata: { title: { en: 'Analytics' } }, version: 1 } });
    } else if (path === `/api/v1/dev-admin/plugins/${pluginId}/revisions` && method === 'GET') {
      await route.fulfill({ json: [] });
    } else if (path === '/api/v1/dev-admin/releases/r2' && method === 'GET') {
      await route.fulfill({ json: {
        releaseId: 'r2', pluginId, version: '2.0.0', state: 'PUBLISHED', approvalState: 'APPROVED',
        rowVersion: 1, manifestSha256: 'sha256:manifest',
        manifest: { packageManifest: { manifestVersion: '2', id: pluginId, version: '2.0.0', image: 'localhost:15000/public/plugins/com.example.analytics/main@sha256:' + '1'.repeat(64), services: [{ id: 'worker', image: 'localhost:15000/public/plugins/com.example.analytics/worker@sha256:' + '2'.repeat(64) }] }, capabilities: [], subscriptions: [] },
        catalog: { icon: 'sha256-' + '3'.repeat(64), screenshots: [{ assetId: 'sha256-' + '4'.repeat(64), order: 0, alt: { en: 'Analytics preview' }, caption: { en: 'Dashboard preview' } }] },
      } });
    } else if (path.startsWith('/api/v1/marketplace/assets/') && method === 'GET') {
      await route.fulfill({
        status: 200,
        contentType: 'image/png',
        body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aDvkAAAAASUVORK5CYII=', 'base64'),
      });
    } else {
      await route.fulfill({ status: 404, json: { error: `Unexpected browser-test request ${method} ${path}` } });
    }
  });

  await page.goto('/dev-admin/');
  await expect(page.getByRole('heading', { name: 'Marketplace control room' })).toBeVisible();
  const analyticsPlugin = page.getByRole('button', { name: /com\.example\.analytics/ });
  const securityPlugin = page.getByRole('button', { name: /com\.example\.security/ });

  await page.getByLabel('Hledat').fill(pluginId);
  await expect(analyticsPlugin).toBeVisible();
  await expect(page.getByRole('heading', { name: 'analytics', exact: true })).toBeVisible();
  await expect(securityPlugin).toHaveCount(0);
  await page.getByRole('button', { name: 'Seznam' }).click();
  await expect(page.getByRole('heading', { name: 'analytics', exact: true })).toHaveCount(0);
  await expect(analyticsPlugin).toBeVisible();
  await page.getByRole('button', { name: 'Kategorie' }).click();
  await page.getByLabel('Hledat').fill('');
  await page.getByLabel('Kategorie').selectOption('security');
  await expect(securityPlugin).toBeVisible();
  await expect(analyticsPlugin).toHaveCount(0);
  await page.getByLabel('Kategorie').selectOption('ALL');
  await page.getByLabel('Moderace').selectOption('SUSPENDED');
  await expect(securityPlugin).toBeVisible();
  await expect(analyticsPlugin).toHaveCount(0);

  await page.getByLabel('Moderace').selectOption('ALL');
  await expect(page.locator('.pagination span')).toHaveText('Strana 1 / 2 · 52 pluginů');
  await page.getByRole('button', { name: 'Další' }).click();
  await expect(page.locator('.pagination span')).toHaveText('Strana 2 / 2 · 52 pluginů');
  await page.getByRole('button', { name: 'Předchozí' }).click();
  await analyticsPlugin.click();
  await expect(page.getByText('v2.0.0')).toBeVisible();
  await expect(page.getByText('v1.0.0')).toBeVisible();
  await page.locator('.release-filter select').first().selectOption('PUBLISHED');
  await expect(page.getByText('v2.0.0')).toBeVisible();
  await expect(page.getByText('v1.0.0')).toHaveCount(0);
  await page.getByRole('button', { name: 'Detail' }).click();
  await expect(page.getByRole('heading', { name: 'com.example.analytics · 2.0.0' })).toBeVisible();
  await expect(page.getByText('localhost:15000/public/plugins/com.example.analytics/main@sha256:' + '1'.repeat(64), { exact: true })).toBeVisible();
  await expect(page.getByText('localhost:15000/public/plugins/com.example.analytics/worker@sha256:' + '2'.repeat(64), { exact: true })).toBeVisible();
  const mediaImages = page.locator('.artifact-browser img');
  await expect(mediaImages).toHaveCount(2);
  await expect.poll(() => mediaImages.first().evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);
  await expect.poll(() => mediaImages.nth(1).evaluate((image: HTMLImageElement) => image.naturalWidth)).toBeGreaterThan(0);
  await expect(page.getByText('Screenshot 1')).toBeVisible();
});
