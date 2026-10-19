import { useState } from 'react'
// DUMMY(US-014): swap to the real master-resume source when the endpoints are ready.
import { createDummyMasterTree } from '../dummy/dummyMasterResume'
import OutlineTree from '../outline/OutlineTree'

export default function MasterResumePage() {
  const [tree, setTree] = useState(createDummyMasterTree)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  return (
    <>
      <h1>Master resume</h1>
      <OutlineTree tree={tree} onChange={setTree} selectedId={selectedId} onSelect={setSelectedId} />
    </>
  )
}
