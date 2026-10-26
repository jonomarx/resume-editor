import { createServer, type Server } from 'node:http'
import type { AddressInfo } from 'node:net'
import { expect, test, type Page } from '@playwright/test'

const PASSWORD = 'correct-horse-battery'

function uniqueName(prefix: string): string {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

/** A page with a schema.org JobPosting in JSON-LD: the extraction path that needs no LLM. */
function jobPage(title: string, url: string): string {
  const posting = {
    '@context': 'https://schema.org',
    '@type': 'JobPosting',
    title,
    description: 'Build and ship software with a small team. Java, SQL.',
    datePosted: '2026-10-01',
    url,
    hiringOrganization: { '@type': 'Organization', name: 'Fixture Co' },
    jobLocation: { '@type': 'Place', address: { '@type': 'PostalAddress', addressLocality: 'Austin', addressRegion: 'TX' } },
  }
  return `<!doctype html><html><head><title>${title}</title><script type="application/ld+json">${JSON.stringify(posting)}</script></head><body><h1>${title}</h1></body></html>`
}

async function signUp(page: Page) {
  await page.goto('/login')
  await page.getByRole('button', { name: 'Need an account? Sign up' }).click()
  await page.getByLabel('Username').fill(uniqueName('e2e'))
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('button', { name: /^Log out/ })).toBeVisible()
}

interface IngestEvent {
  id: number
  status: string
  jobId: number | null
  error: { code: string; message: string } | null
}

async function events(page: Page): Promise<IngestEvent[]> {
  return (await page.request.get('/api/ingest/events')).json()
}

async function pastePosting(page: Page, kind: string, payload: string) {
  await page.goto('/add-job')
  await page.getByLabel('What are you pasting?').selectOption(kind)
  await page.getByLabel('Posting', { exact: true }).fill(payload)
  await page.getByRole('button', { name: 'Add posting' }).click()
}

test('add sources: refused local address, accepted name that is refused at crawl time', async ({ page }) => {
  await signUp(page)
  await page.goto('/sources')
  await expect(page.getByText('No sources yet')).toBeVisible()

  // A literal local address is refused at once (SSRF protection), and nothing is listed.
  await page.getByLabel('Career page URL').fill('http://localhost/careers')
  await page.getByRole('button', { name: 'Add source' }).click()
  await expect(page.getByRole('alert')).toContainText('public website')
  await expect(page.getByText('No sources yet')).toBeVisible()

  // A name is accepted (it is only resolved when fetched) and listed as not crawled yet.
  // The app container maps fixture-site.example to 10.0.0.5 (a private address), see README.
  const name = uniqueName('Fixture')
  await page.getByLabel('Career page URL').fill('http://fixture-site.example/careers')
  await page.getByLabel('Name (optional)').fill(name)
  await page.getByRole('button', { name: 'Add source' }).click()
  const row = page.getByRole('listitem', { name })
  await expect(row).toBeVisible()
  await expect(row).toContainText('fixture-site.example/careers')

  // The same URL again is a duplicate.
  await page.getByLabel('Career page URL').fill('http://fixture-site.example/careers/')
  await page.getByRole('button', { name: 'Add source' }).click()
  await expect(page.getByRole('alert')).toContainText('already have this source')

  // The scheduled run (APP_JOBS_DAILY_RUN_CRON in the README) crawls it, the policy refuses the private address,
  // and the Sources page shows the failure instead of postings.
  await expect(async () => {
    await page.reload()
    await expect(page.getByRole('listitem', { name })).toContainText('url_not_allowed', { timeout: 2_000 })
  }).toPass({ timeout: 90_000, intervals: [3_000] })
  await expect(page.getByRole('listitem', { name })).toContainText('Last run failed')
  await expect(page.getByRole('listitem', { name })).toContainText('0 active, 0 stale postings')
})

test('paste a posting: added, duplicate recognised, unreadable text fails visibly', async ({ page }) => {
  await signUp(page)
  const title = uniqueName('Pasted Intern')
  const html = jobPage(title, `https://fixture-co.example/jobs/${title}`)

  await pastePosting(page, 'html', html)
  await expect(page.getByRole('status')).toContainText('Done. The posting was added.', { timeout: 30_000 })
  const done = (await events(page)).filter((e) => e.status === 'done')
  expect(done).toHaveLength(1)
  expect(done[0].jobId).not.toBeNull()

  // The same page again creates nothing new.
  await pastePosting(page, 'html', html)
  await expect(page.getByRole('status')).toContainText('You already added this posting.')
  expect((await events(page)).filter((e) => e.status === 'done')).toHaveLength(1)

  // Free text needs an LLM; with no usable LLM configured, so the failure is shown.
  await pastePosting(page, 'text', `Software Engineering Intern at Fixture Co ${title}. Apply now.`)
  await expect(page.getByRole('status')).toContainText('Could not process it', { timeout: 30_000 })
  const failed = (await events(page)).find((e) => e.status === 'failed')
  // BUG #270: backend US-110 makes LLM_PROVIDER=none the default, so the code becomes llm_unavailable once
  // role/backend reaches integration. Accept both until then; the next step tightens this to llm_unavailable.
  expect(['llm_unavailable', 'extraction_failed']).toContain(failed?.error?.code)
})

test('bookmarklet posts cross-origin with a bearer token (CORS), and a revoked token is refused', async ({ page, request }) => {
  const title = uniqueName('Bookmarked Intern')
  let site!: Server
  await new Promise<void>((resolve) => {
    site = createServer((req, res) => {
      res.setHeader('content-type', 'text/html')
      res.end(jobPage(title, `https://fixture-co.example/jobs/${title}`))
    }).listen(0, '127.0.0.1', resolve)
  })
  const siteOrigin = `http://127.0.0.1:${(site.address() as AddressInfo).port}`
  try {
    await signUp(page)
    await page.goto('/add-job')
    await page.getByLabel('Token name').fill('e2e bookmarklet')
    await page.getByRole('button', { name: 'Create token' }).click()
    const code = (await page.getByLabel('Bookmarklet code').inputValue()).replace(/^javascript:/, '')
    expect(code).toContain('Bearer ing_')

    // The preflight the browser sends is answered without credentials, and only for this route.
    const base = new URL(page.url()).origin
    expect(new URL(siteOrigin).origin).not.toBe(base)
    const pre = await request.fetch(`${base}/api/ingest`, {
      method: 'OPTIONS',
      headers: { Origin: siteOrigin, 'Access-Control-Request-Method': 'POST', 'Access-Control-Request-Headers': 'authorization,content-type' },
    })
    expect(pre.status()).toBe(204)
    expect(pre.headers()['access-control-allow-origin']).toBe('*')
    expect(pre.headers()['access-control-allow-headers']).toContain('Authorization')
    expect(pre.headers()['access-control-allow-credentials']).toBeUndefined()

    // Run the bookmarklet on a page from another origin, as the browser would.
    const jobTab = await page.context().newPage()
    await jobTab.goto(siteOrigin)
    const alerts: string[] = []
    jobTab.on('dialog', async (d) => {
      alerts.push(d.message())
      await d.dismiss()
    })
    await jobTab.evaluate(code)
    await expect.poll(() => alerts, { timeout: 15_000 }).toEqual(['Sent to Resume Editor'])
    await expect
      .poll(async () => (await events(page)).filter((e) => e.status === 'done' && e.jobId !== null).length, { timeout: 30_000 })
      .toBe(1)

    // The token shows as used; after revoking it, the same bookmarklet is rejected with 401.
    await page.reload()
    const tokenRow = page.getByRole('listitem', { name: 'e2e bookmarklet' })
    await expect(tokenRow).toContainText('last used')
    await tokenRow.getByRole('button', { name: 'Revoke e2e bookmarklet' }).click()
    await expect(tokenRow).toContainText('revoked')
    alerts.length = 0
    await jobTab.evaluate(code)
    await expect.poll(() => alerts, { timeout: 15_000 }).toEqual(['Resume Editor rejected it (401)'])
  } finally {
    await new Promise<void>((r) => site.close(() => r()))
  }
})
