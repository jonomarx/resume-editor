import { expect, test, type Page } from '@playwright/test'

const PASSWORD = 'correct-horse-battery'

// Unique per run so reruns against the same database never collide (pattern ^[A-Za-z0-9_.-]{3,40}$).
function uniqueUsername(): string {
  return `e2e_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 8)}`
}

async function submitCredentials(page: Page, username: string, password: string, button: string) {
  await page.getByLabel('Username').fill(username)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: button, exact: true }).click()
}

async function expectSignedIn(page: Page, username: string) {
  await expect(page).not.toHaveURL(/\/login/)
  await expect(page.getByRole('heading', { name: 'Resume Editor' })).toBeVisible()
  await expect(page.getByRole('button', { name: `Log out (${username})` })).toBeVisible()
}

async function logOut(page: Page, username: string) {
  await page.getByRole('button', { name: `Log out (${username})` }).click()
  await expect(page.getByRole('heading', { name: 'Log in' })).toBeVisible()
  await expect(page.getByRole('button', { name: /^Log out/ })).toHaveCount(0)
}

test('sign up, log out, log in, log out, wrong password', async ({ page }) => {
  const username = uniqueUsername()

  // Route guard: signed out, a protected page redirects to the login page.
  await page.goto('/resume')
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('heading', { name: 'Log in' })).toBeVisible()

  // Sign up lands signed in, back on the page we asked for.
  await page.getByRole('button', { name: 'Need an account? Sign up' }).click()
  await expect(page.getByRole('heading', { name: 'Sign up' })).toBeVisible()
  await submitCredentials(page, username, PASSWORD, 'Create account')
  await expect(page).toHaveURL(/\/resume$/)
  await expect(page.getByRole('heading', { name: 'Master resume' })).toBeVisible()
  await expect(page.getByRole('button', { name: `Log out (${username})` })).toBeVisible()

  // The session survives a reload (server-side session, not just client state).
  await page.reload()
  await expect(page.getByRole('button', { name: `Log out (${username})` })).toBeVisible()

  // Log out; the guard applies again.
  await logOut(page, username)
  await page.goto('/jobs')
  await expect(page).toHaveURL(/\/login$/)

  // Log back in with the same credentials.
  await submitCredentials(page, username, PASSWORD, 'Log in')
  await expect(page).toHaveURL(/\/jobs$/)
  await expect(page.getByRole('heading', { name: 'Jobs' })).toBeVisible()
  await page.goto('/')
  await expectSignedIn(page, username)

  // Log out again; a reload confirms the server session is gone too.
  await logOut(page, username)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Log in' })).toBeVisible()

  // Wrong password shows an error and stays signed out.
  await submitCredentials(page, username, 'not-the-password', 'Log in')
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('button', { name: /^Log out/ })).toHaveCount(0)
})

test('duplicate username is rejected at sign-up', async ({ page }) => {
  const username = uniqueUsername()
  await page.goto('/login')
  await page.getByRole('button', { name: 'Need an account? Sign up' }).click()
  await submitCredentials(page, username, PASSWORD, 'Create account')
  await expect(page.getByRole('button', { name: `Log out (${username})` })).toBeVisible()
  await page.getByRole('button', { name: `Log out (${username})` }).click()

  await page.getByRole('button', { name: 'Need an account? Sign up' }).click()
  await submitCredentials(page, username.toUpperCase(), PASSWORD, 'Create account')
  await expect(page.getByRole('alert')).toBeVisible()
  await expect(page.getByRole('button', { name: /^Log out/ })).toHaveCount(0)
})
