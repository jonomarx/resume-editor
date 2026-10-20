import { useId, useState } from 'react'
import type { Tag, TagCategory } from '../api/types'
import { describeTagError, type TagError } from './tagErrors'
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
  const [error, setError] = useState<TagError | null>(null)
  const [tipOpen, setTipOpen] = useState(false)
  const tipId = useId()
  const q = query.trim().toLowerCase()
  const nameOf = (slug: string) => vocabulary.find((t) => t.slug === slug)?.name ?? slug
  const candidates = q ? vocabulary.filter((t) => !selected.includes(t.slug) && matches(t, q)).slice(0, TAG_SUGGESTION_LIMIT) : []
  const exact = vocabulary.some((t) => t.name.toLowerCase() === q || t.slug === q)

  const add = (slug: string) => {
    onAdd(slug)
    setQuery('')
    setError(null)
  }

  async function create() {
    setError(null)
    try {
      add((await onCreate(query.trim(), category)).slug)
    } catch (e) {
      setError(describeTagError(e))
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
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? tipId : undefined}
          onKeyDown={(e) => e.key === 'Escape' && setTipOpen(false)}
        />
      </label>
      {error && (
        <span style={{ position: 'relative' }}>
          <button
            type="button"
            aria-label={TAG_LABELS.errorIndicator}
            aria-describedby={tipId}
            onMouseEnter={() => setTipOpen(true)}
            onMouseLeave={() => setTipOpen(false)}
            onFocus={() => setTipOpen(true)}
            onBlur={() => setTipOpen(false)}
            onKeyDown={(e) => e.key === 'Escape' && setTipOpen(false)}
          >
            {TAG_LABELS.errorMark}
          </button>
          <span id={tipId} role="tooltip" hidden={!tipOpen} style={{ position: 'absolute', left: 0, top: '100%', background: '#222', color: '#fff', padding: '4px 8px', width: 'max-content', maxWidth: '20rem', zIndex: 10 }}>
            {error.message}
            {error.signIn && (
              <>
                {' '}
                <a href="/login" style={{ color: 'inherit' }}>{TAG_LABELS.signIn}</a>
              </>
            )}
          </span>
        </span>
      )}
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
    </section>
  )
}
