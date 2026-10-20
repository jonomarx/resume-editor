// Test-only fake of GET/POST /api/tags with the slug rules of the contract (simplified: ASCII names).
import type { Tag, TagCategory } from '../api/types'

export interface FakeTags {
  handle(method: string, path: string, body: unknown): Response | null
  vocabulary: Tag[]
  /** Test hook: make create fail with an HTTP error body, or throw a network error. */
  failCreate: { status: number; body: unknown } | 'network' | null
}

export const slugify = (name: string) =>
  name.trim().toLowerCase().replace(/\+/g, '-plus-').replace(/#/g, '-sharp-').replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')

export function createFakeTags(): FakeTags {
  const vocabulary: Tag[] = [
    { id: 1, name: 'Java', slug: 'java', category: 'language', aliases: ['JDK'] },
    { id: 2, name: 'React', slug: 'react', category: 'framework', aliases: [] },
    { id: 3, name: 'Python', slug: 'python', category: 'language', aliases: [] },
  ]
  const fake: FakeTags = {
    vocabulary,
    failCreate: null,
    handle(method, path, body) {
      if (path !== '/api/tags') return null
      if (method === 'GET') return new Response(JSON.stringify(vocabulary), { status: 200 })
      if (fake.failCreate === 'network') throw new TypeError('Failed to fetch')
      if (fake.failCreate) return new Response(JSON.stringify(fake.failCreate.body), { status: fake.failCreate.status })
      const { name, category } = body as { name: string; category: TagCategory }
      const slug = slugify(name)
      const existing = vocabulary.find((t) => t.slug === slug || t.aliases.some((a) => a.toLowerCase() === name.trim().toLowerCase()))
      if (existing) return new Response(JSON.stringify(existing), { status: 200 })
      const tag: Tag = { id: vocabulary.length + 1, name: name.trim(), slug, category, aliases: [] }
      vocabulary.push(tag)
      return new Response(JSON.stringify(tag), { status: 201 })
    },
  }
  return fake
}
