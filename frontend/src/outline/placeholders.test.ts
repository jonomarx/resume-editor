import { PLACEHOLDERS } from '../config/constants'
import { createNode } from './treeOps'

const id = () => 'x'

describe('placeholder content for new nodes', () => {
  it('section', () => {
    const n = createNode('section', id)
    expect(n.title).toBe('New Section')
    expect(n.latex).toBe('\\section{New Section}')
  })

  it('section text', () => {
    const n = createNode('section_text', id)
    expect(n.title).toBe('University Name, Degree, Aug. 2025 -- May 2028')
    expect(n.latex).toContain(PLACEHOLDERS.sectionText.text)
  })

  it('subsection', () => {
    const n = createNode('subsection', id)
    expect(n.title).toBe('Company Name')
    for (const s of ['Company Name', 'Job Title', 'City, ST', 'Mon. YYYY -- Mon. YYYY']) expect(n.latex).toContain(s)
  })

  it('bullet', () => {
    const n = createNode('subsection_text', id)
    expect(n.title).toBe(PLACEHOLDERS.bullet)
    expect(n.latex).toBe(`\\resumeItem{${PLACEHOLDERS.bullet}}`)
    expect(n.latex).toContain('Describe what you did')
  })

  it('new nodes have no tags and no children', () => {
    expect(createNode('section', id)).toMatchObject({ tags: [], children: [] })
  })
})

describe('no inline placeholder strings', () => {
  const sources = import.meta.glob<string>(['../**/*.{ts,tsx}', '!../**/*.test.*', '!../dummy/**', '!../api/schema.d.ts', '!../config/constants.ts'], {
    query: '?raw',
    import: 'default',
    eager: true,
  })

  it('appear only in constants.ts', () => {
    const literals = [
      PLACEHOLDERS.section.title,
      PLACEHOLDERS.sectionText.text,
      PLACEHOLDERS.subsection.organization,
      PLACEHOLDERS.subsection.role,
      PLACEHOLDERS.subsection.location,
      PLACEHOLDERS.subsection.dates,
      PLACEHOLDERS.bullet,
    ]
    expect(Object.keys(sources).length).toBeGreaterThan(5)
    const offenders = Object.entries(sources).filter(([, text]) => literals.some((l) => text.includes(l))).map(([f]) => f)
    expect(offenders).toEqual([])
  })
})
