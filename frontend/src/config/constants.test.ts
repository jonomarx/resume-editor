import { PLACEHOLDERS, RENDER_DEBOUNCE_MS } from './constants'

describe('constants', () => {
  it('exports the documented render debounce', () => {
    expect(RENDER_DEBOUNCE_MS).toBe(10000)
  })

  it('exports the documented placeholder strings', () => {
    expect(PLACEHOLDERS.section.title).toBe('New Section')
    expect(PLACEHOLDERS.sectionText.text).toBe('University Name, Degree, Aug. 2025 -- May 2028')
    expect(PLACEHOLDERS.subsection).toEqual({
      organization: 'Company Name',
      role: 'Job Title',
      location: 'City, ST',
      dates: 'Mon. YYYY -- Mon. YYYY',
    })
    expect(PLACEHOLDERS.bullet).toBe(
      'Describe what you did, starting with a strong verb and ending with a result you can measure.',
    )
  })
})
