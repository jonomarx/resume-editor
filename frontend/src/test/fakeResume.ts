// Test-only fake of the master-resume endpoints (read, save with optimistic locking, versions, restore).
import type { ResumeDocument, ResumeTree } from '../api/types'
import { createSampleTree } from './sampleTree'

export interface FakeResume {
  /** Returns a response for resume paths, or null when the path is not a resume path. */
  handle(method: string, path: string, body: unknown): Response | null
  /** Test hook: another tab saves this tree. */
  saveElsewhere(tree: ResumeTree): void
  /** Test hook: reject the next save with a 500. */
  failNextSave(): void
  saves: { baseVersion: number; tree: ResumeTree }[]
}

export function createFakeResume(): FakeResume {
  const versions: ResumeDocument[] = []
  let failNext = false
  const saves: FakeResume['saves'] = []
  const doc = (version: number, tree: ResumeTree): ResumeDocument => ({
    id: 1,
    version,
    contentHash: String(version).padStart(64, '0'),
    updatedAt: new Date(2026, 0, version).toISOString(),
    tree,
  })
  versions.push(doc(1, createSampleTree()))
  const current = () => versions[versions.length - 1]
  const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status })
  const conflict = () => json(409, { code: 'version_conflict', message: 'The resume changed since you loaded it', currentVersion: current().version })

  return {
    saves,
    saveElsewhere: (tree) => void versions.push(doc(current().version + 1, tree)),
    failNextSave: () => {
      failNext = true
    },
    handle(method, path, body) {
      const b = body as { baseVersion?: number; tree?: ResumeTree } | undefined
      if (path === '/api/resumes/master' && method === 'GET') return json(200, current())
      if (path === '/api/resumes/master' && method === 'PUT') {
        if (failNext) {
          failNext = false
          return json(500, { code: 'server_error', message: 'Boom' })
        }
        saves.push({ baseVersion: b!.baseVersion!, tree: b!.tree! })
        if (b!.baseVersion !== current().version) return conflict()
        versions.push(doc(current().version + 1, b!.tree!))
        return json(200, current())
      }
      if (path === '/api/resumes/master/versions' && method === 'GET') {
        return json(200, [...versions].reverse().map((v) => ({ version: v.version, contentHash: v.contentHash, createdAt: v.updatedAt })))
      }
      const m = /^\/api\/resumes\/master\/versions\/(\d+)\/restore$/.exec(path)
      if (m && method === 'POST') {
        const old = versions.find((v) => v.version === Number(m[1]))
        if (!old) return json(404, { code: 'not_found', message: 'No such version' })
        if (b!.baseVersion !== current().version) return conflict()
        versions.push(doc(current().version + 1, old.tree))
        return json(200, current())
      }
      return null
    },
  }
}
