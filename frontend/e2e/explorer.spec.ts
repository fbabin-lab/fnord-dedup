import { test, expect, Page } from '@playwright/test';

test.use({actionTimeout:15000});

test('stored explorer keeps two-tab drafts, escapes notes, freezes bulk edits and retains location notes on rescan',async({page,context},testInfo)=>{
  test.skip(process.env['FNORD_EXPECT_NATIVE_FIXTURES']!=='true','Requires generated native fixtures; never targets operator sources.');
  test.setTimeout(180000);
  await page.goto('/'); await page.getByLabel('Username').fill('operator'); await page.getByLabel('Password',{exact:true}).fill(process.env['FNORD_TEST_PASSWORD']!);
  await page.getByRole('button',{name:'Sign in',exact:true}).click();
  await expect(page.getByRole('heading',{name:'Inventory ready'})).toBeVisible();
  const token=(await context.cookies()).find(c=>c.name==='XSRF-TOKEN')!.value;
  const sources=await (await page.request.get('/api/v1/sources')).json();
  async function scan(name:string):Promise<string> {
    const response=await page.request.post('/api/v1/scans',{headers:{'X-XSRF-TOKEN':token,'Idempotency-Key':name.replaceAll(' ','-')},data:{name,sourceIds:[sources.sources[0].id]}});
    expect(response.status()).toBe(202); const created=await response.json();
    await expect.poll(async()=> (await (await page.request.get('/api/v1/scans/'+created.scanId)).json()).job.state,{timeout:90000}).toBe('COMPLETED');
    return created.scanId;
  }
  const id=await scan('M3 browser fixture');
  await page.goto(`/scans/${id}/files`);
  await expect(page.getByRole('heading',{name:'M3 browser fixture'})).toBeVisible();
  await expect(page.locator('tbody tr')).toHaveCount(100);
  await page.getByRole('button',{name:'Expand Generated native fixture',exact:true}).click();
  await expect(page.getByRole('button',{name:'Expand nested',exact:true})).toBeVisible();
  const sourceButton=page.locator('aside').getByRole('button',{name:'Generated native fixture',exact:true});
  await sourceButton.click(); await expect(page.getByRole('navigation',{name:'Directory breadcrumbs'})).toBeVisible();
  await page.getByRole('button',{name:'All selected sources',exact:true}).click();
  async function selectFile(view:Page):Promise<void> {
    await view.getByLabel('Filename contains',{exact:true}).fill('file-0002.txt');
    await view.getByRole('button',{name:'Search stored files',exact:true}).click();
    await expect(view.locator('tbody tr')).toHaveCount(1);
    await view.getByRole('button',{name:'file-0002.txt',exact:true}).click();
    await expect(view.getByLabel('Location memo',{exact:true})).toBeVisible();
  }
  await selectFile(page);
  const memo='<img src=x onerror=alert(1)>\nLiteral %_ location memo';
  const tag='<script>inert tag</script>';
  await page.getByLabel('Location memo',{exact:true}).fill(memo);
  const editor=page.getByRole('region',{name:'Location annotations'});
  await editor.getByText('Choose or manage reusable tags',{exact:true}).click();
  await editor.getByLabel('Create tag label',{exact:true}).fill(tag);
  await editor.getByRole('button',{name:'Create tag',exact:true}).click();
  await expect(editor.getByRole('button',{name:'Remove '+tag,exact:true})).toBeVisible();
  await page.getByRole('button',{name:'Save location notes',exact:true}).click();
  await expect(page.getByText('Location notes saved.',{exact:true})).toBeVisible();
  expect(await page.locator('img').count()).toBe(0);
  expect(await page.locator('script').evaluateAll(nodes=>nodes.some(n=>n.textContent?.includes('inert tag')))).toBe(false);
  const other=await context.newPage(); await other.goto(`/scans/${id}/files`); await selectFile(other);
  await expect(other.getByLabel('Location memo',{exact:true})).toHaveValue(memo);
  await page.getByLabel('Location memo',{exact:true}).fill('first tab saved');
  await page.getByRole('button',{name:'Save location notes',exact:true}).click();
  await expect(page.getByText('Location notes saved.',{exact:true})).toBeVisible();
  await other.getByLabel('Location memo',{exact:true}).fill('second tab draft stays here');
  await other.getByRole('button',{name:'Save location notes',exact:true}).click();
  await expect(other.getByRole('alert').filter({hasText:'These notes changed'})).toBeVisible();
  await expect(other.getByLabel('Location memo',{exact:true})).toHaveValue('second tab draft stays here');
  await other.screenshot({path:testInfo.outputPath('m3-conflict.png'),fullPage:true});
  await other.getByRole('button',{name:'Discard draft and reload',exact:true}).click();
  await expect(other.getByLabel('Location memo',{exact:true})).toHaveValue('first tab saved');
  await page.getByRole('button',{name:'Close file details',exact:true}).click();
  await page.getByRole('button',{name:'Refresh results',exact:true}).click();
  await page.getByRole('button',{name:'Freeze all matching results',exact:true}).click();
  const bulk=page.getByRole('region',{name:'Frozen bulk review'});
  await expect(bulk.getByText('1 observations · 22 known logical bytes',{exact:true})).toBeVisible();
  await bulk.getByLabel('Bulk review state',{exact:true}).selectOption('KEEP');
  await bulk.getByRole('button',{name:'Apply to 1 observations',exact:true}).click();
  await expect(page.getByText('Updated 1 frozen observations.',{exact:true})).toBeVisible();
  await expect(page.locator('tbody')).toContainText('KEEP');
  await page.getByRole('button',{name:'Freeze all matching results',exact:true}).click();
  await bulk.getByRole('button',{name:'Calculate frozen checksums',exact:true}).click();
  await expect(page.getByRole('status').filter({hasText:'Checksum job'})).toBeVisible();
  await expect.poll(async()=>{
    const result=await page.request.post(`/api/v1/scans/${id}/files/search`,{headers:{'X-XSRF-TOKEN':token},data:{filters:{nameExact:'file-0002.txt'}}});
    return (await result.json()).items[0].hashStatus;
  },{timeout:30000}).toBe('ACCEPTED');
  await page.getByRole('button',{name:'Refresh results',exact:true}).click();
  await expect(page.locator('tbody')).toContainText('ACCEPTED');
  await page.screenshot({path:testInfo.outputPath('m3-explorer.png'),fullPage:true,animations:'disabled'});
  const second=await scan('M3 rescan fixture'); await other.goto(`/scans/${second}/files`); await selectFile(other);
  await expect(other.getByLabel('Location memo',{exact:true})).toHaveValue('first tab saved');
  await expect(other.getByLabel('Review state',{exact:true})).toHaveValue('KEEP');
  await other.getByRole('button',{name:'View location history',exact:true}).click();
  await expect(other.getByText('Separate observations of this location; these rows are not separate duplicate copies.',{exact:true})).toBeVisible();
  await other.setViewportSize({width:680,height:900}); await other.screenshot({path:testInfo.outputPath('m3-responsive.png'),fullPage:true});
  expect(await other.evaluate(()=>document.documentElement.scrollWidth<=window.innerWidth)).toBe(true);
});
