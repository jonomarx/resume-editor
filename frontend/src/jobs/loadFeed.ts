import type { JobQuery, JobSummary, JobsApi } from '../api/types'

export interface FeedPage {
  items: JobSummary[]
  /** Server offset where the next page starts. */
  next: number
  hasMore: boolean
  /** Server total; counts hidden postings too when hideDismissed is set. */
  total: number
}

/**
 * Loads one page of the feed. The contract's `status` filter takes one value, so "everything but dismissed" is done here:
 * dismissed postings are dropped from each server page, and further server pages are requested until the page is full or
 * the feed ends. `offset` is therefore a server offset, and the page reports where the next one starts.
 */
export async function loadFeedPage(jobs: JobsApi, query: JobQuery, hideDismissed: boolean): Promise<FeedPage> {
  if (!hideDismissed) {
    const p = await jobs.list(query)
    const next = query.offset + p.items.length
    return { items: p.items, next, hasMore: next < p.total, total: p.total }
  }
  const items: JobSummary[] = []
  let offset = query.offset
  for (;;) {
    const p = await jobs.list({ ...query, offset })
    for (const [i, item] of p.items.entries()) {
      if (item.userStatus === 'dismissed') continue
      if (items.length === query.limit) return { items, next: offset + i, hasMore: true, total: p.total }
      items.push(item)
    }
    offset += p.items.length
    if (p.items.length === 0 || offset >= p.total) return { items, next: offset, hasMore: false, total: p.total }
  }
}
