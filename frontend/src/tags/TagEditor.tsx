import { useState } from 'react'
import type { Tag, TagCategory } from '../api/types'
import { TAG_CATEGORIES, TAG_LABELS } from '../config/constants'

interface Props {
  /** Slugs on the node. */
  selected: string[]
  vocabulary: Tag[]
  onChange(slugs: string[]): void
  /** Get-or-create in the vocabulary; resolves to the entry to use. */
  onCreate(name: string, category: TagCategory): Promise<Tag>
}

const matches = (t: Tag, q: string) =>
  t.name.toLowerCase().includes(q) || t.slug.includes(q) || t.aliases.some((a) => a.toLowerCase().includes(q))

export default function TagEditor({ selected, vocabulary, onChange, onCreate }: Props) {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<TagCategory>(TAG_CATEGORIES[0].value)
  const [error, setError] = useState<string | null>(null)
  const q = query.trim().toLowerCase()
  const nameOf = (slug: string) => vocabulary.find((t) => t.slug === slug)?.name ?? slug
  const candidates = q ? vocabulary.filter((t) => !selected.includes(t.slug) && matches(t, q)) : []
  const exact = vocabulary.some((t) => t.name.toLowerCase() === q || t.slug === q)

  const add = (slug: string) => {
    if (!selected.includes(slug)) onChange([...selected, slug])
    setQuery('')
  }

  async function create() {
    setError(null)
    try {
      add((await onCreate(query.trim(), category)).slug)
    } catch {
      setError(TAG_LABELS.createFailed)
    }
  }

  return (
    <section aria-label={TAG_LABELS.heading}>
      <h2>{TAG_LABELS.heading}</h2>
      {selected.length === 0 ? (
        <p>{TAG_LABELS.none}</p>
      ) : (
        <ul>
          {selected.map((slug) => (
            <li key={slug}>
              {nameOf(slug)}{' '}
              <button type="button" aria-label={TAG_LABELS.remove(nameOf(slug))} onClick={() => onChange(selected.filter((s) => s !== slug))}>
                x
              </button>
            </li>
          ))}
        </ul>
      )}
      <label>
        {TAG_LABELS.addLabel}
        <input value={query} onChange={(e) => setQuery(e.target.value)} />
      </label>
      {candidates.length > 0 && (
        <ul aria-label="Matching tags">
          {candidates.map((t) => (
            <li key={t.slug}>
              <button type="button" onClick={() => add(t.slug)}>{t.name}</button>
            </li>
          ))}
        </ul>
      )}
      {q && !exact && (
        <div>
          <label>
            {TAG_LABELS.categoryLabel}
            <select value={category} onChange={(e) => setCategory(e.target.value as TagCategory)}>
              {TAG_CATEGORIES.map((c) => (
                <option key={c.value} value={c.value}>{c.label}</option>
              ))}
            </select>
          </label>
          <button type="button" onClick={() => void create()}>{TAG_LABELS.create(query.trim())}</button>
        </div>
      )}
      {error && <p role="alert">{error}</p>}
    </section>
  )
}
