// DUMMY(US-014): hard-coded master resume standing in for GET /api/resumes/master. Delete when US-014 is ready.
import type { ResumeNode, ResumeTree, NodeType } from '../outline/types'

// Latex follows the {{children}} contract: parents carry the token inside their wrapper macros, leaves carry none.
const latexFor = (type: NodeType, title: string): string => {
  switch (type) {
    case 'section':
      return `\\section{${title}}\n\\resumeSubHeadingListStart\n{{children}}\n\\resumeSubHeadingListEnd`
    case 'subsection':
      return `\\resumeSubheading{${title}}{City, ST}{Role}{Dates}\n\\resumeItemListStart\n{{children}}\n\\resumeItemListEnd`
    case 'section_text':
      return `\\resumeSubheading{${title}}{}{}{}`
    case 'subsection_text':
      return `\\resumeItem{${title}}`
    default:
      return title
  }
}

const node = (id: string, type: NodeType, title: string, children: ResumeNode[] = []): ResumeNode => ({
  id,
  type,
  title,
  latex: latexFor(type, title),
  tags: [],
  children,
})

export function createDummyMasterTree(): ResumeTree {
  return {
    id: 'dummy-master',
    header: node('h', 'header', 'Your Name'),
    sections: [
      node('s-edu', 'section', 'Education', [node('e1', 'section_text', 'University Name, B.S. Computer Science')]),
      node('s-exp', 'section', 'Experience', [
        node('x1', 'subsection', 'Acme Corp', [
          node('x1b1', 'subsection_text', 'Built a thing'),
          node('x1b2', 'subsection_text', 'Shipped another thing'),
        ]),
        node('x2', 'subsection', 'Globex', [node('x2b1', 'subsection_text', 'Fixed a bug')]),
      ]),
    ],
    footer: node('f', 'footer', 'Footer'),
  }
}
