import { useState } from 'react'
import type { ResumeVersionSummary } from '../api/types'
import { SAVE_LABELS } from '../config/constants'

interface Props {
  versions: ResumeVersionSummary[] | null
  currentVersion: number
  canRestore: boolean
  onOpen(): void
  onRestore(version: number): void
}

export default function VersionHistory({ versions, currentVersion, canRestore, onOpen, onRestore }: Props) {
  const [open, setOpen] = useState(false)
  return (
    <section aria-label={SAVE_LABELS.history}>
      <button
        type="button"
        aria-expanded={open}
        onClick={() => {
          if (!open) onOpen()
          setOpen(!open)
        }}
      >
        {SAVE_LABELS.history}
      </button>
      {open && (
        <>
          {!canRestore && <p>{SAVE_LABELS.restoreBlocked}</p>}
          {versions && versions.length === 0 && <p>{SAVE_LABELS.historyEmpty}</p>}
          <ul>
            {versions?.map((v) => (
              <li key={v.version}>
                Version {v.version} ({new Date(v.createdAt).toLocaleString()}){v.version === currentVersion ? ' - current' : ''}{' '}
                {v.version !== currentVersion && (
                  <button type="button" disabled={!canRestore} aria-label={`${SAVE_LABELS.restore} version ${v.version}`} onClick={() => onRestore(v.version)}>
                    {SAVE_LABELS.restore}
                  </button>
                )}
              </li>
            ))}
          </ul>
        </>
      )}
    </section>
  )
}
