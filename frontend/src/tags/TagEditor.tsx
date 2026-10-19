import { useState } from 'react'
import type { Tag, TagCategory } from '../api/types'
import { TAG_CATEGORIES, TAG_LABELS, TAG_SUGGESTION_LIMIT } from '../config/constants'

interface Props {
  /** Slugs on the node. */
  selected: string[]
  vocabulary: Tag[]
  onAdd(slug: string): void
  onRemove(slug: string): void
  /** Get-or-create in the vocabulary; resolves to the entry to use. */
  onCreate(name: string, category: TagCategory): Promise<Tag>
}

const matches = (t: Tag, q: string) =>
  t.name.toLowerCase().includes(q) || t.slug.includes(q) || t.aliases.some((a) => a.toLowerCase().includes(q))

export default function TagEditor({ selected, vocabulary, onAdd, onRemove, onCreate }: Props) {
  const [query, setQuery] = useState('')
  const [category, setCategory] = useState<TagCategory>(TAG_CATEGORIES[0].value)
  const [error, setError] = useState<string | null>(null)
  const q = query.trim().toLowerCase()
  const nameOf = (slug: string) => vocabulary.find((t) => t.slug === slug)?.name ?? slug
  const candidates = q ? vocabulary.filter((t) => !selected.includes(t.slug) && matches(t, q)).slice(0, TAG_SUGGESTION_LIMIT) : []
  const exact = vocabulary.some((t) => t.name.toLowerCase() === q || t.slug === q)

  const add = (slug: string) => {
    onAdd(slug)
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
            <li key={slug} style={{ overflowWrap: 'anywhere' }}>
              {nameOf(slug)}{' '}
              <button type="button" aria-label={TAG_LABELS.remove(nameOf(slug))} onClick={() => onRemove(slug)}>
                {TAG_LABELS.removeSymbol}
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
        <ul aria-label={TAG_LABELS.matchingLabel}>
          {candidates.map((t) => (
            <li key={t.slug} style={{ overflowWrap: 'anywhere' }}>
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
