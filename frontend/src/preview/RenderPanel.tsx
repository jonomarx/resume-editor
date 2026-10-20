import { PREVIEW_LABELS } from '../config/constants'
import { findNode } from '../outline/treeOps'
import type { ResumeTree } from '../outline/types'
import PdfViewer from './PdfViewer'
import type { RenderState } from './useRender'

interface Props extends RenderState {
  tree: ResumeTree
  renderNow(): void
}

export default function RenderPanel({ tree, rendering, result, error, renderNow }: Props) {
  const failed = result && !result.success
  return (
    <section aria-label="Preview">
      <button type="button" onClick={renderNow} disabled={rendering}>
        {rendering ? PREVIEW_LABELS.rendering : PREVIEW_LABELS.renderNow}
      </button>
      {result?.success && result.pageCount != null && <span data-testid="page-count"> {PREVIEW_LABELS.pages(result.pageCount)}</span>}
      {error && (
        <div role="alert">
          {PREVIEW_LABELS.failed} {error}
        </div>
      )}
      {failed && (
        <div role="alert">
          <strong>{PREVIEW_LABELS.errorsTitle}</strong>
          <ul>
            {result.errors.map((e, i) => {
              const node = e.nodeId ? findNode(tree, e.nodeId)?.node : null
              return (
                <li key={i}>
                  {node ? node.title || node.id : PREVIEW_LABELS.preamble}
                  {e.line != null ? `, line ${e.line}` : ''}: {e.message}
                </li>
              )
            })}
          </ul>
          <details>
            <summary>{PREVIEW_LABELS.logSummary}</summary>
            <pre>{result.log}</pre>
          </details>
        </div>
      )}
      {result?.success && result.pdfUrl ? <PdfViewer url={result.pdfUrl} /> : !failed && !error && <p>{PREVIEW_LABELS.noPreview}</p>}
    </section>
  )
}
