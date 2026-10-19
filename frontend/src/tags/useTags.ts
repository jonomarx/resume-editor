import { useCallback, useEffect, useState } from 'react'
import type { Api, Tag, TagCategory } from '../api/types'
import { TAG_LABELS } from '../config/constants'

/** The tag vocabulary: loaded once, and updated when the editor creates an entry. */
export function useTags(api: Api) {
  const [tags, setTags] = useState<Tag[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    api.tags
      .listTags()
      .then((t) => !cancelled && setTags(t))
      .catch(() => !cancelled && setError(TAG_LABELS.loadFailed))
    return () => {
      cancelled = true
    }
  }, [api])

  /** Gets or creates the entry first, so a node can only ever reference a slug that exists. */
  const create = useCallback(
    async (name: string, category: TagCategory): Promise<Tag> => {
      const tag = await api.tags.createTag(name, category)
      setTags((prev) => (prev.some((t) => t.slug === tag.slug) ? prev : [...prev, tag]))
      return tag
    },
    [api],
  )

  return { tags, error, create }
}
