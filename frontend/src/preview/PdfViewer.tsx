import { useEffect, useRef, useState } from 'react'
import * as pdfjs from 'pdfjs-dist'
import workerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
import { PDF_PREVIEW_SCALE } from '../config/constants'

pdfjs.GlobalWorkerOptions.workerSrc = workerUrl

/** Draws every page of the PDF at `url` (same-origin, session cookie) onto canvases with PDF.js. */
export default function PdfViewer({ url }: { url: string }) {
  const host = useRef<HTMLDivElement>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const task = pdfjs.getDocument({ url, withCredentials: true })
    task.promise
      .then(async (pdf) => {
        const canvases: HTMLCanvasElement[] = []
        for (let n = 1; n <= pdf.numPages; n++) {
          const page = await pdf.getPage(n)
          const viewport = page.getViewport({ scale: PDF_PREVIEW_SCALE })
          const canvas = document.createElement('canvas')
          canvas.width = viewport.width
          canvas.height = viewport.height
          canvas.style.display = 'block'
          canvas.style.marginBottom = '8px'
          await page.render({ canvas, viewport }).promise
          canvases.push(canvas)
        }
        if (!cancelled) {
          setError(null)
          host.current?.replaceChildren(...canvases)
        }
      })
      .catch((e: unknown) => !cancelled && setError(e instanceof Error ? e.message : String(e)))
    return () => {
      cancelled = true
      void task.destroy()
    }
  }, [url])

  return (
    <>
      {error && <p role="alert">{error}</p>}
      <div ref={host} aria-label="PDF preview" />
    </>
  )
}
