import { INGEST_LABELS, INGEST_MAX_PAYLOAD_CHARS } from '../config/constants'

/**
 * Builds the bookmarklet: a `javascript:` URL that posts the current page's HTML to POST /api/ingest with the
 * ingest-only bearer token. Origin, token and messages are embedded as JSON string literals, so quotes or other
 * special characters in them cannot break out of the code. It runs inside the job page, so the token is readable
 * by that page's scripts: that is why only an ingest-only, revocable token is ever used here.
 */
export function buildBookmarkletCode(origin: string, token: string): string {
  const L = INGEST_LABELS
  return (
    '(function(){' +
    `var u=${JSON.stringify(origin + '/api/ingest')};` +
    `fetch(u,{method:'POST',headers:{'Authorization':${JSON.stringify('Bearer ' + token)},'Content-Type':'application/json'},` +
    `body:JSON.stringify({kind:'html',payload:document.documentElement.outerHTML.slice(0,${INGEST_MAX_PAYLOAD_CHARS}),sourceHint:location.href.slice(0,500)})})` +
    `.then(function(r){alert(r.ok?${JSON.stringify(L.alertSent)}:${JSON.stringify(L.alertFailed(0))}.replace('0',r.status))})` +
    `.catch(function(){alert(${JSON.stringify(L.alertUnreachable)})});` +
    '})();'
  )
}

export const bookmarkletUrl = (code: string): string => 'javascript:' + encodeURIComponent(code)
