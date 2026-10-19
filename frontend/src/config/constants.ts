// Frontend tunables and placeholder text (DESIGN section 4). Never inline these elsewhere.

/** The single token in a node's `latex` where its children's LaTeX goes (api-contract ResumeNode). Literal, case-sensitive. */
export const CHILDREN_TOKEN = '{{children}}'

export const RENDER_DEBOUNCE_MS = 10000

/** Quiet time after the last edit before the master resume is saved. */
export const AUTOSAVE_DEBOUNCE_MS = 2000

export const PLACEHOLDERS = {
  section: {
    title: 'New Section',
  },
  sectionText: {
    text: 'University Name, Degree, Aug. 2025 -- May 2028',
  },
  subsection: {
    organization: 'Company Name',
    role: 'Job Title',
    location: 'City, ST',
    dates: 'Mon. YYYY -- Mon. YYYY',
  },
  bullet:
    'Describe what you did, starting with a strong verb and ending with a result you can measure.',
} as const

export const AUTH = {
  usernameMinLength: 3,
  usernameMaxLength: 40,
  passwordMinLength: 8,
  usernamePattern: '^[A-Za-z0-9_.-]{3,40}$',
} as const

export const LOADING_TEXT = 'Loading...'

export const CSRF = {
  cookieName: 'XSRF-TOKEN',
  headerName: 'X-XSRF-TOKEN',
  tokenPath: '/api/me',
  invalidCode: 'csrf_invalid',
} as const

export const OUTLINE_LABELS = {
  untitled: '(untitled)',
  add: {
    header: 'Add header',
    footer: 'Add footer',
    section: 'Add section',
    section_text: 'Add text line',
    subsection: 'Add subsection',
    subsection_text: 'Add bullet',
  },
} as const

// TEMPLATE: these snippets use Jake's macro names. Replace them when the derivative template (US-011)
// changes the macros. `$0` marks where the cursor lands after insertion.
export const LATEX_SNIPPETS = [
  { label: 'Bold', text: '\\textbf{$0}' },
  { label: 'Italic', text: '\\textit{$0}' },
  { label: 'Link', text: '\\href{https://$0}{link text}' },
  { label: 'Bullet', text: '\\resumeItem{$0}' },
  { label: 'Bullet list', text: '\\resumeItemListStart\n  \\resumeItem{$0}\n\\resumeItemListEnd' },
  // Restores the children token if it was deleted; a node must contain exactly one.
  { label: 'Children', text: CHILDREN_TOKEN },
  { label: 'Subheading', text: '\\resumeSubheading\n  {$0}{City, ST}\n  {Job Title}{Mon. YYYY -- Mon. YYYY}' },
] as const

export const EDITOR_LABELS = {
  empty: 'Select a node in the outline to edit its LaTeX.',
  editorAria: 'LaTeX editor',
} as const

// TEMPLATE: list wrapper macros (Jake's names) that surround a parent's children token. A node carries its
// wrapper and token only while it has children, because an empty list environment does not compile.
export const LIST_WRAPPERS = {
  section: { start: '\\resumeSubHeadingListStart', end: '\\resumeSubHeadingListEnd' },
  subsection: { start: '\\resumeItemListStart', end: '\\resumeItemListEnd' },
} as const

/** The text appended to a parent's latex when it gains its first child. */
export const childrenBlock = (type: keyof typeof LIST_WRAPPERS): string =>
  `\n${LIST_WRAPPERS[type].start}\n${CHILDREN_TOKEN}\n${LIST_WRAPPERS[type].end}`

// TEMPLATE: LaTeX for a new node, using Jake's macro names. Each string is a function of the placeholder text
// above, so the visible words live only in PLACEHOLDERS. A new section or subsection is only its heading line;
// the wrapper and token arrive with its first child (see addNode).
export const PLACEHOLDER_LATEX = {
  section: (p: typeof PLACEHOLDERS) => `\\section{${p.section.title}}`,
  sectionText: (p: typeof PLACEHOLDERS) => `\\resumeSubheading{${p.sectionText.text}}{}{}{}`,
  subsection: (p: typeof PLACEHOLDERS) =>
    `\\resumeSubheading{${p.subsection.organization}}{${p.subsection.location}}{${p.subsection.role}}{${p.subsection.dates}}`,
  bullet: (p: typeof PLACEHOLDERS) => `\\resumeItem{${p.bullet}}`,
} as const

export const TOKEN_WARNINGS = {
  missing: `This node has children but its LaTeX has no ${CHILDREN_TOKEN} token, so it cannot be saved. Add the token where the children should go.`,
  duplicate: `This node's LaTeX has more than one ${CHILDREN_TOKEN} token. Keep exactly one.`,
  emptyWrapper: `This node has no children but its LaTeX still has a ${CHILDREN_TOKEN} token inside list macros; an empty list does not compile. Remove the list macros and the token, or add a child.`,
  unknown: (token: string) => `Unknown token ${token}: the only token is ${CHILDREN_TOKEN} (it is case-sensitive).`,
} as const

export const SAVE_LABELS = {
  loading: 'Loading your resume...',
  saved: 'All changes saved',
  dirty: 'Unsaved changes',
  saving: 'Saving...',
  conflict: 'This resume was changed somewhere else (another tab or device). Your last edits were not saved.',
  reload: 'Reload latest version',
  error: 'Saving failed.',
  retry: 'Retry save',
  history: 'Version history',
  restore: 'Restore',
  historyEmpty: 'No versions yet.',
  restoreBlocked: 'Wait for saving to finish before restoring.',
} as const

export const PREVIEW_LABELS = {
  renderNow: 'Render now',
  rendering: 'Rendering...',
  pages: (n: number) => `${n} ${n === 1 ? 'page' : 'pages'}`,
  errorsTitle: 'Could not render',
  logSummary: 'Compile log',
  noPreview: 'No preview yet.',
  preamble: 'the template',
  failed: 'Rendering failed.',
} as const

/** PDF.js draws each page at this scale (1 = 72 dpi). */
export const PDF_PREVIEW_SCALE = 1.2

export const TAG_CATEGORIES = [
  { value: 'language', label: 'Language' },
  { value: 'framework', label: 'Framework' },
  { value: 'domain', label: 'Domain' },
  { value: 'role_type', label: 'Role type' },
] as const

export const TAG_LABELS = {
  heading: 'Tags',
  none: 'No tags yet.',
  addLabel: 'Add a tag',
  categoryLabel: 'Category for a new tag',
  create: (name: string) => `Create "${name}"`,
  remove: (name: string) => `Remove tag ${name}`,
  loadFailed: 'Could not load tags.',
  createFailed: 'Could not create the tag.',
  /** Nodes of these types carry tags (DESIGN section 4); header and footer do not. */
  taggable: ['section', 'section_text', 'subsection', 'subsection_text'],
} as const
