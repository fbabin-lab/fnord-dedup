import { test, expect } from '@playwright/test';

test('native inventory pauses, resumes, survives closing the view and browses stored results', async ({ page, context }, testInfo) => {
  test.skip(process.env['FNORD_EXPECT_NATIVE_FIXTURES'] !== 'true', 'Requires the generated native fixture server; never targets operator sources.');
  test.setTimeout(120000);
  await page.goto('/');
  await page.getByLabel('Username').fill('operator');
  await page.getByLabel('Password',{exact:true}).fill(process.env['FNORD_TEST_PASSWORD']!);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Inventory ready'})).toBeVisible();
  await page.getByRole('link',{name:'Scans',exact:true}).click();
  await page.getByLabel('Scan name').fill('Native browser inventory');
  await page.getByRole('checkbox').first().check();
  await page.getByRole('button',{name:'Start scan',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Native browser inventory'})).toBeVisible();
  const scanId = page.url().split('/').pop();
  const token = (await context.cookies()).find(c => c.name === 'XSRF-TOKEN')!.value;
  let scan = await (await page.request.get('/api/v1/scans/'+scanId)).json();
  const paused = await page.request.post('/api/v1/jobs/'+scan.job.id+'/pause', {headers:{'X-XSRF-TOKEN':token}});
  expect(paused.ok()).toBe(true);
  await expect.poll(async () => (await (await page.request.get('/api/v1/scans/'+scanId)).json()).job.state).toBe('PAUSED');
  await page.reload();
  await expect(page.getByRole('button',{name:'Resume',exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Resume',exact:true}).click();
  await page.close();
  // Closing the view stops polling; the server-owned worker still finishes.
  const reopened = await context.newPage();
  await expect.poll(async () => (await (await reopened.request.get('/api/v1/scans/'+scanId)).json()).job.state,{timeout:90000}).toBe('COMPLETED');
  await reopened.goto('/scans/'+scanId);
  await expect(reopened.getByText('Inventory finished.',{exact:true})).toBeVisible();
  await reopened.screenshot({path:testInfo.outputPath('inventory-progress.png')});
  scan = await (await reopened.request.get('/api/v1/scans/'+scanId)).json();
  expect(scan.job.discoveredEntries).toBe('1255');
  expect(scan.sources[0].coverage).toBe('COMPLETE');
  await reopened.getByRole('button',{name:'Generated native fixture',exact:true}).click();
  await expect(reopened.locator('section').filter({has:reopened.getByRole('heading',{name:'Committed observations',exact:true})}).locator('tbody tr')).toHaveCount(100);
  await expect(reopened.getByRole('button',{name:'Next entries',exact:true})).toBeVisible();
  await reopened.getByRole('button',{name:/file-\d+\.txt/}).first().click();
  await expect(reopened.getByRole('heading',{name:'Observation detail'})).toBeVisible();
  await expect(reopened.getByText('Exact relative path (base64)')).toBeVisible();
  expect(await reopened.locator('img').count()).toBe(0);
  await expect(reopened.getByRole('heading',{name:'Duplicate groups',exact:true})).toBeVisible();
  await reopened.getByRole('button',{name:'2cf24dba5fb0a30e…',exact:true}).click();
  await expect(reopened.getByRole('heading',{name:'Group detail',exact:true})).toBeVisible();
  await expect(reopened.getByText('HASH_IDENTICAL · DISTINCT_OBJECTS · 1 participating roots')).toBeVisible();
  const root = scan.sources[0].rootLocationId;
  let cursor: string | null = null;
  let unique: {id: string} | undefined;
  do {
    const entries = await (await reopened.request.get(`/api/v1/scans/${scanId}/directories/${root}/children?limit=500`+(cursor ? '&cursor='+encodeURIComponent(cursor) : ''))).json();
    unique = entries.items.find((entry: {name: string}) => entry.name === 'file-0002.txt');
    cursor = entries.nextCursor;
  } while (!unique && cursor);
  expect(unique).toBeDefined();
  // Use a stored observation ID to select the unique file; the UI supplies the explicit consent.
  await reopened.getByRole('button',{name:'Refresh entries',exact:true}).click();
  const selected = await (await reopened.request.get('/api/v1/observations/'+unique!.id)).json();
  expect(selected.hash.status).toBe('NOT_REQUESTED_UNIQUE_SIZE');
  // Directory enumeration order is filesystem-dependent, so page to this exact stored name.
  for (let i=0; i<13 && await reopened.getByRole('button',{name:'file-0002.txt',exact:true}).count() === 0; i++)
    await Promise.all([reopened.waitForResponse(r => r.url().includes('/children?') && r.ok()),reopened.getByRole('button',{name:'Next entries',exact:true}).click()]);
  await reopened.getByRole('button',{name:'file-0002.txt',exact:true}).click();
  await expect(reopened.getByText('NOT_REQUESTED_UNIQUE_SIZE',{exact:true})).toBeVisible();
  await reopened.getByRole('button',{name:'Calculate checksum',exact:true}).click();
  await expect.poll(async () => (await (await reopened.request.get('/api/v1/observations/'+unique!.id)).json()).hash.status).toBe('ACCEPTED');
  await reopened.getByRole('button',{name:'Refresh progress',exact:true}).click();
  await expect(reopened.getByRole('button',{name:'Force fresh checksum',exact:true})).toBeVisible();
  await reopened.getByRole('button',{name:'View attempt history',exact:true}).click();
  await expect(reopened.getByText(/ACCEPTED · 22 bytes/)).toBeVisible();
  await reopened.screenshot({path:testInfo.outputPath('m2-hash-evidence.png'),fullPage:true});
});
