# Decision record 002: our resume template and node LaTeX (US-011)

Status: accepted (2026-10-19). Owner decisions; changes come as a new story.

## The template is a derivative of Jake's

`docs/reference/jake-resume-template.tex` is the reference. Our template keeps **Jake's layout, Jake's fonts and
Jake's macro names** (`\resumeSubheading`, `\resumeItem`, `\resumeProjectHeading`, `\resumeSubHeadingListStart`, and
the rest). Today the derivative differs from the reference only in how it is split up (below); revisions to the
look come later as their own stories.

## Hidden, locked header

Everything before `\begin{document}` (document class, packages, `glyphtounicode`, margins, section formatting and all
macro definitions) is the **hidden locked header**. It lives only in the template resource
`backend/core/src/main/resources/template/preamble.tex`. It is not part of the resume tree, is never shown to the user
(not even in an Advanced view), is not editable through the node tree or the API, and no API response contains it.
`ResumeTree` and `ResumeNode` have no field for it, and a test checks that no controller holds the template or the
assembler. Swapping the template means editing that one folder (`preamble.tex`, `document-end.tex`,
`starter-tree.json`); the assembler takes the preamble from nowhere else.

## A node's `latex` is its own LaTeX in full

(Contract 0.2.1, `ResumeNode`.) A node's `latex` holds its heading or subheading macro, any list wrapper macros, and
the place where its children go: the single literal, case-sensitive token `{{children}}`. The assembler replaces the
token with the expanded children in `children` order, nested, so reordering children never edits any `latex`.

- A node with children must contain exactly one token; none, or more than one, is a validation error naming the node.
- A token in a childless node is removed.
- No other `{{...}}` token exists; any other is a validation error. The token counts anywhere, including inside a
  LaTeX comment or verbatim text.
- `title` is only the outline label and is never written into the document.
- The assembler writes `%%NODE <id>` before and `%%END <id>` after every node's expansion, so a compile error line maps
  back to the innermost node containing it (`AssembledDocument.nodeIdAtLine`). Node ids are limited to
  `[A-Za-z0-9_-]{1,64}` by the schema, so an id cannot inject text into a marker.
- A section whose children mix text nodes and subsections expands them in `children` order at the one token.

## Starter tree: how Jake's sample maps onto nodes

`starter-tree.json` (seeded for new users by US-014) is Jake's sample content under four sections, as DESIGN
section 4 describes:

| Jake sample | Node |
| --- | --- |
| `\begin{center}...\end{center}` heading block | `header` node |
| `\section{Education}` with `\resumeSubHeadingListStart/End` | `section` node "Education": `\section{..}`, list-start macro, `{{children}}`, list-end macro |
| each `\resumeSubheading` school | `section_text` node (no bullets, so no token) |
| `\section{Experience}` | `section` node, same wrapper |
| each experience `\resumeSubheading` with its `\resumeItemListStart/End` | `subsection` node: the heading macro, item-list-start, `{{children}}`, item-list-end |
| each `\resumeItem{..}` | `subsection_text` node |
| `\section{Projects}` with `\resumeProjectHeading` entries | `section` of `subsection` nodes (project heading macro) with bullets |
| `\section{Technical Skills}` (one `itemize` with four `\\`-separated lines) | `section` "Skills" with four `section_text` nodes, one per line, each `\small{\item{\textbf{Category}{: ...}}}`; the section wrapper is the same `itemize` as `\resumeSubHeadingListStart` |
| text before `\end{document}` | `footer` node (empty) |

Differences from the reference, all deliberate: the section "Technical Skills" is titled "Skills" (owner decision 3);
each skills line is its own item (so each line can be tagged), which can add a little vertical space between lines
compared with the single item with line breaks; the commented-out alternatives in the reference are not carried over.
The starter assembled document compiles in the renderer container (US-012) to exactly one page (slow-loop test
`TemplateCompileTests`).

## Dependency

`com.networknt:json-schema-validator` 1.5.9 validates trees against `schema/resume-tree.schema.json` (draft-07). The
design asks for a JSON schema; this library is the maintained Jackson-based validator, and the Boot BOM has none.
