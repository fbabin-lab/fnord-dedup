import { test, expect } from '@playwright/test';

test('authenticated foundation uses the real API, separate CSRF cookie, and logout', async ({ page, request }) => {
  const password = process.env['FNORD_TEST_PASSWORD'];
  if (!password) throw new Error('Set FNORD_TEST_PASSWORD for a disposable test deployment.');
  const data = await request.get('/api/v1/sources');
  expect(data.status()).toBe(401);
  await page.goto('/');
  await expect(page.getByRole('heading', { name:'Welcome to Fnord Dedup' })).toBeVisible();
  await expect(page.locator('header')).toHaveCSS('display','flex');
  await expect(page.locator('.panel')).toHaveCSS('padding-top','28px');
  await page.getByLabel('Username').fill(process.env['FNORD_TEST_USERNAME'] || 'operator');
  await page.getByLabel('Password', { exact:true }).fill(password);
  await page.getByRole('button', {name:'Sign in',exact:true}).click();
  await expect(page.getByRole('heading', {name:'Inventory ready'})).toBeVisible();
  await expect(page.getByText('SHA-256 duplicate analysis.')).toBeVisible();
  const cookies = await page.context().cookies();
  const session = cookies.find(c => c.name === 'JSESSIONID');
  expect(session?.httpOnly).toBe(true); expect(session?.sameSite).toBe('Strict');
  expect(cookies.find(c => c.name === 'XSRF-TOKEN')?.httpOnly).toBe(false);
  const missingCsrf = await page.request.post('/api/v1/session/logout');
  expect(missingCsrf.status()).toBe(403);
  await page.getByRole('link', {name:'Sources',exact:true}).click();
  await expect(page.getByRole('heading', {name:'Sources',exact:true})).toBeVisible();
  const sources = await (await page.request.get('/api/v1/sources')).json();
  expect(sources.configurationRevision).toMatch(/^[0-9a-f]{64}$/);
  if (process.env['FNORD_EXPECT_MOUNT_FIXTURES'] === 'true') {
    expect(sources.sources.find((s: {key:string}) => s.key === 'read-only').status).toBe('AVAILABLE');
    expect(sources.sources.find((s: {key:string}) => s.key === 'writable').status).toBe('WRITABLE_SOURCE');
    await expect(page.getByText('WRITABLE_SOURCE', {exact:true})).toBeVisible();
  } else if (sources.sources.length === 0) {
    await expect(page.getByRole('heading',{name:'No sources configured'})).toBeVisible();
  }
  await page.getByRole('link', {name:'Scans',exact:true}).click();
  await expect(page.getByRole('heading', {name:'New scan'})).toBeVisible();
  expect((await page.request.get('/api/v1/scans')).status()).toBe(200);
  expect((await page.request.post('/api/v1/scans',{data:{name:'Unauthorized mutation',sourceIds:[]}})).status()).toBe(403);
  if (process.env['FNORD_EXPECT_MOUNT_FIXTURES'] === 'true') {
    await page.getByLabel('Scan name').fill('Disposable mounted inventory');
    await page.getByRole('checkbox').first().check();
    await page.getByRole('button',{name:'Start scan',exact:true}).click();
    await expect(page.getByRole('heading',{name:'Disposable mounted inventory'})).toBeVisible();
    await expect.poll(async () => {
      const id = page.url().split('/').pop();
      return (await (await page.request.get('/api/v1/scans/'+id)).json()).job.state;
    }).toBe('COMPLETED');
    await page.reload();
    await expect(page.getByRole('heading',{name:'Disposable mounted inventory'})).toBeVisible();
    await page.getByRole('button',{name:'read-only',exact:true}).click();
    await expect(page.getByRole('heading',{name:'Committed observations'})).toBeVisible();
    await expect(page.locator('tbody tr')).not.toHaveCount(0);
  }
  await page.getByRole('button',{name:'Sign out'}).click();
  await expect(page.getByRole('button',{name:'Sign in',exact:true})).toBeVisible();
  expect((await page.request.get('/api/v1/sources')).status()).toBe(401);
});
