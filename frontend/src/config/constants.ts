// Frontend tunables and placeholder text (DESIGN section 4). Never inline these elsewhere.

export const RENDER_DEBOUNCE_MS = 10000

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
  { label: 'Subheading', text: '\\resumeSubheading\n  {$0}{City, ST}\n  {Job Title}{Mon. YYYY -- Mon. YYYY}' },
] as const

export const EDITOR_LABELS = {
  empty: 'Select a node in the outline to edit its LaTeX.',
  editorAria: 'LaTeX editor',
} as const
