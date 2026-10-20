import { expect, test, type Page } from '@playwright/test'

const PASSWORD = 'correct-horse-battery'
const BULLET = 'Used Celery and Redis for asynchronous tasks'
const SECTION_PLACEHOLDER = 'New Section'
const TEXT_LINE_PLACEHOLDER = 'University Name, Degree, Aug. 2025 -- May 2028'

function uniqueName(prefix: string): string {
  return `${prefix}_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

async function signUp(page: Page) {
  await page.goto('/resume')
  await page.getByRole('button', { name: 'Need an account? Sign up' }).click()
  await page.getByLabel('Username').fill(uniqueName('e2e'))
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Master resume' })).toBeVisible()
}

const outlineItem = (page: Page, name: string | RegExp) =>
  page.getByRole('tree', { name: 'Resume outline' }).getByRole('button', { name, exact: typeof name === 'string' })
const editor = (page: Page) => page.getByRole('textbox', { name: 'LaTeX editor' })
const saved = (page: Page) => expect(page.getByRole('status')).toHaveText('All changes saved', { timeout: 15_000 })

async function setEditorText(page: Page, text: string) {
  await editor(page).click()
  await page.keyboard.press('ControlOrMeta+a')
  await page.keyboard.insertText(text)
}

async function waitForPreview(page: Page, pages = '1 page') {
  await page.getByRole('button', { name: 'Render now' }).click()
  await expect(page.getByTestId('page-count')).toHaveText(pages, { timeout: 60_000 })
  await expect(page.getByLabel('PDF preview').locator('canvas')).toHaveCount(Number(pages.split(' ')[0]))
}

async function currentVersion(page: Page): Promise<number> {
  const text = await page.getByRole('listitem').filter({ hasText: '- current' }).first().innerText()
  return Number(/Version (\d+)/.exec(text)![1])
}

test('edit, add nodes, tag, preview, reload, restore a version', async ({ page }) => {
  await signUp(page)

  // The starter tree is in the outline, saved, and previews as one page.
  await expect(outlineItem(page, 'Education')).toBeVisible()
  await expect(outlineItem(page, 'Experience')).toBeVisible()
  await expect(outlineItem(page, 'Skills')).toBeVisible()
  await expect(outlineItem(page, BULLET)).toBeVisible()
  await saved(page)
  await waitForPreview(page)
  await expect(page.getByRole('alert')).toHaveCount(0)

  // Edit a node's LaTeX.
  await outlineItem(page, BULLET).click()
  await expect(editor(page)).toContainText(BULLET)
  const edit1 = '\\resumeItem{First e2e edit of this bullet}'
  await setEditorText(page, edit1)
  await expect(editor(page)).toContainText('First e2e edit of this bullet')

  // Add a section and a child: both appear with the placeholder text.
  await page.getByRole('button', { name: 'Add section', exact: true }).click()
  await expect(outlineItem(page, SECTION_PLACEHOLDER)).toBeVisible()
  await page.getByRole('button', { name: `Add text line under ${SECTION_PLACEHOLDER}` }).click()
  await expect(outlineItem(page, TEXT_LINE_PLACEHOLDER)).toBeVisible()
  await outlineItem(page, SECTION_PLACEHOLDER).click()
  await expect(editor(page)).toContainText(`\\section{${SECTION_PLACEHOLDER}}`)
  await expect(editor(page)).toContainText('{{children}}')
  await outlineItem(page, TEXT_LINE_PLACEHOLDER).click()
  await expect(editor(page)).toContainText(TEXT_LINE_PLACEHOLDER)

  // Tag the new text line: create a plain tag, then a symbol tag whose slug is c-plus-plus.
  const tagName = uniqueName('Tag')
  const tags = page.getByRole('region', { name: 'Tags' })
  await tags.getByLabel('Add a tag').fill(tagName)
  await tags.getByRole('button', { name: `Create "${tagName}"` }).click()
  await expect(tags.getByRole('button', { name: `Remove tag ${tagName}` })).toBeVisible()
  await tags.getByLabel('Add a tag').fill('C++')
  await tags.getByLabel('Category for a new tag').selectOption({ label: 'Language' })
  await tags.getByRole('button', { name: 'Create "C++"' }).click()
  await expect(tags.getByRole('button', { name: 'Remove tag C++' })).toBeVisible()

  // The vocabulary resolves C++ to the slug c-plus-plus.
  const vocab = await (await page.request.get('/api/tags')).json()
  expect(vocab.find((t: { name: string }) => t.name === 'C++')?.slug).toBe('c-plus-plus')

  // A node with children compiles: no error alerts, still one page.
  await outlineItem(page, SECTION_PLACEHOLDER).click()
  await expect(page.getByRole('alert')).toHaveCount(0)
  await saved(page)
  await waitForPreview(page)
  await expect(page.getByRole('alert')).toHaveCount(0)
  await expect(page.getByText('Could not render')).toHaveCount(0)

  // The saved master resume carries the edit and both tags (checked server-side, not just in the UI).
  const body = JSON.stringify(await (await page.request.get('/api/resumes/master')).json())
  expect(body).toContain('First e2e edit of this bullet')
  expect(body).toContain('c-plus-plus')

  // Reload: the edit, the new nodes and the tags persist.
  await page.reload()
  await expect(outlineItem(page, SECTION_PLACEHOLDER)).toBeVisible()
  await outlineItem(page, BULLET).click()
  await expect(editor(page)).toContainText('First e2e edit of this bullet')
  await outlineItem(page, TEXT_LINE_PLACEHOLDER).click()
  await expect(tags.getByRole('button', { name: `Remove tag ${tagName}` })).toBeVisible()
  await expect(tags.getByRole('button', { name: 'Remove tag C++' })).toBeVisible()
  await saved(page)

  // Second edit, then restore the version that held the first edit.
  await outlineItem(page, BULLET).click()
  await setEditorText(page, '\\resumeItem{Second e2e edit, to be undone}')
  await expect(editor(page)).toContainText('Second e2e edit')
  await saved(page)
  await page.getByRole('button', { name: 'Version history' }).click()
  const before = await currentVersion(page)
  expect(before).toBeGreaterThan(1)
  await page.getByRole('button', { name: `Restore version ${before - 1}` }).click()

  // Content went back, and the restore created a new version rather than rewriting history.
  await expect(editor(page)).toContainText('First e2e edit of this bullet')
  await expect(editor(page)).not.toContainText('Second e2e edit')
  await expect(page.getByRole('listitem').filter({ hasText: `Version ${before + 1}` })).toContainText('current')
  expect(await currentVersion(page)).toBe(before + 1)
  await saved(page)
  await page.reload()
  await outlineItem(page, BULLET).click()
  await expect(editor(page)).toContainText('First e2e edit of this bullet')
  await expect(editor(page)).not.toContainText('Second e2e edit')
})
