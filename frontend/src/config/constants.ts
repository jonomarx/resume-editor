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
  passwordMaxLength: 200,
} as const

export const LOADING_TEXT = 'Loading...'
