// DUMMY(US-014): hard-coded master resume standing in for GET /api/resumes/master. Delete when US-014 is ready.
import type { ResumeNode, ResumeTree, NodeType } from '../outline/types'

const node = (id: string, type: NodeType, title: string, children: ResumeNode[] = []): ResumeNode => ({
  id,
  type,
  title,
  latex: '',
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
