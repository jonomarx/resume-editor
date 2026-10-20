/** Splits a snippet at its `$0` cursor marker. Without a marker the cursor goes to the end. */
export function expandSnippet(text: string): { insert: string; cursor: number } {
  const i = text.indexOf('$0')
  if (i < 0) return { insert: text, cursor: text.length }
  return { insert: text.slice(0, i) + text.slice(i + 2), cursor: i }
}
