import { useState } from 'react'
// DUMMY(US-014): swap to the real master-resume source when the endpoints are ready.
import { createDummyMasterTree } from '../dummy/dummyMasterResume'
import NodeEditor from '../editor/NodeEditor'
import OutlineTree from '../outline/OutlineTree'

export default function MasterResumePage() {
  const [tree, setTree] = useState(createDummyMasterTree)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  return (
    <>
      <h1>Master resume</h1>
      <div style={{ display: 'flex', gap: '1rem' }}>
        <OutlineTree tree={tree} onChange={setTree} selectedId={selectedId} onSelect={setSelectedId} />
        <NodeEditor tree={tree} selectedId={selectedId} onChange={setTree} />
      </div>
    </>
  )
}
