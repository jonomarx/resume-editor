import { useState } from 'react'
import { api } from '../api'
import { SAVE_LABELS } from '../config/constants'
import NodeEditor from '../editor/NodeEditor'
import OutlineTree from '../outline/OutlineTree'
import RenderPanel from '../preview/RenderPanel'
import { useRender } from '../preview/useRender'
import { useMasterResume } from '../resume/useMasterResume'
import VersionHistory from '../resume/VersionHistory'
import type { Api } from '../api/types'

export default function MasterResumePage({ apiClient = api }: { apiClient?: Api }) {
  const r = useMasterResume(apiClient)
  const preview = useRender(apiClient, r.tree)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  return (
    <>
      <h1>Master resume</h1>
      <p role="status">{r.status === 'conflict' || r.status === 'error' ? '' : SAVE_LABELS[r.status]}</p>
      {r.status === 'conflict' && (
        <div role="alert">
          {SAVE_LABELS.conflict} <button type="button" onClick={() => void r.reload()}>{SAVE_LABELS.reload}</button>
        </div>
      )}
      {r.status === 'error' && (
        <div role="alert">
          {SAVE_LABELS.error} {r.errorMessage} {r.tree && <button type="button" onClick={() => void r.retry()}>{SAVE_LABELS.retry}</button>}
        </div>
      )}
      {r.tree && (
        <>
          <div style={{ display: 'flex', gap: '1rem' }}>
            <OutlineTree tree={r.tree} onChange={r.setTree} selectedId={selectedId} onSelect={setSelectedId} />
            <NodeEditor tree={r.tree} selectedId={selectedId} onChange={r.setTree} />
            <RenderPanel tree={r.tree} {...preview} />
          </div>
          <VersionHistory
            versions={r.versions}
            currentVersion={r.version}
            canRestore={r.status === 'saved'}
            onOpen={() => void r.loadVersions()}
            onRestore={(v) => void r.restore(v)}
          />
        </>
      )}
    </>
  )
}
