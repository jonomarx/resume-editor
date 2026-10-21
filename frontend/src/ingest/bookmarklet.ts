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
    // t() cuts to n UTF-16 units without leaving half of a surrogate pair at the end.
    "function t(s,n){s=s.slice(0,n);var c=s.charCodeAt(s.length-1);return c>=55296&&c<=56319?s.slice(0,-1):s}" +
    `var u=${JSON.stringify(origin + '/api/ingest')};` +
    `fetch(u,{method:'POST',headers:{'Authorization':${JSON.stringify('Bearer ' + token)},'Content-Type':'application/json'},` +
    `body:JSON.stringify({kind:'html',payload:t(document.documentElement.outerHTML,${INGEST_MAX_PAYLOAD_CHARS}),sourceHint:t(location.href,500)})})` +
    `.then(function(r){alert(r.ok?${JSON.stringify(L.alertSent)}:${JSON.stringify(L.alertFailedPrefix)}+r.status+${JSON.stringify(L.alertFailedSuffix)})})` +
    `.catch(function(){alert(${JSON.stringify(L.alertUnreachable)})});` +
    '})();'
  )
}

export const bookmarkletUrl = (code: string): string => 'javascript:' + encodeURIComponent(code)
