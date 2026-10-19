package app.resume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.resume.resumes.InvalidTreeException;
import app.resume.resumes.LatexAssembler;
import app.resume.resumes.NodeType;
import app.resume.resumes.ResumeNode;
import app.resume.resumes.ResumeTree;
import app.resume.resumes.ResumeTreeValidator;
import app.resume.resumes.StarterTree;
import app.resume.resumes.AssembledDocument;
import app.resume.resumes.TemplateResources;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

class ResumeTreeTests {
    final ObjectMapper mapper = new ObjectMapper();
    final ResumeTreeValidator validator = new ResumeTreeValidator(mapper);
    final StarterTree starter = new StarterTree(mapper);

    JsonNode starterJson() { return mapper.valueToTree(starter.create()); }

    static ObjectNode node(ObjectMapper m, String id, String type) {
        ObjectNode n = m.createObjectNode();
        n.put("id", id).put("type", type).put("title", id).put("latex", "");
        n.set("tags", m.createArrayNode());
        n.set("children", m.createArrayNode());
        return n;
    }

    ObjectNode minimalTree() {
        ObjectNode t = mapper.createObjectNode();
        t.put("id", "root");
        t.set("header", node(mapper, "h", "header"));
        t.set("sections", mapper.createArrayNode());
        t.set("footer", node(mapper, "f", "footer"));
        return t;
    }

    // ---- schema accept / reject ----

    @Test
    void starterTreeAndMinimalTreeAreValid() {
        assertThat(validator.validate(starterJson())).isEmpty();
        assertThat(validator.validate(minimalTree())).isEmpty();
    }

    @Test
    void rejectsASubsectionNestedInsideASubsection() {
        ObjectNode t = minimalTree();
        ObjectNode section = node(mapper, "s", "section");
        ObjectNode sub = node(mapper, "sub", "subsection");
        ((ArrayNode) sub.get("children")).add(node(mapper, "deep", "subsection"));
        ((ArrayNode) section.get("children")).add(sub);
        ((ArrayNode) t.get("sections")).add(section);
        assertThat(validator.validate(t)).isNotEmpty();
    }

    @Test
    void rejectsChildrenOnLeafNodesAndWrongPlacement() {
        ObjectNode t = minimalTree();
        ((ArrayNode) ((ObjectNode) t.get("header")).get("children")).add(node(mapper, "x", "section_text"));
        assertThat(validator.validate(t)).isNotEmpty();

        ObjectNode t2 = minimalTree();
        ((ArrayNode) t2.get("sections")).add(node(mapper, "bad", "subsection")); // sections must be type section
        assertThat(validator.validate(t2)).isNotEmpty();

        ObjectNode t3 = minimalTree();
        ObjectNode section = node(mapper, "s", "section");
        ((ArrayNode) section.get("children")).add(node(mapper, "b", "subsection_text")); // bullet directly in section
        ((ArrayNode) t3.get("sections")).add(section);
        assertThat(validator.validate(t3)).isNotEmpty();
    }

    @Test
    void rejectsBadIdsTagsAndExtraProperties() {
        ObjectNode t = minimalTree();
        ((ObjectNode) t.get("header")).put("id", "has space\n%%NODE forged");
        assertThat(validator.validate(t)).isNotEmpty();

        ObjectNode t2 = minimalTree();
        ((ArrayNode) ((ObjectNode) t2.get("header")).get("tags")).add("Not A Slug");
        assertThat(validator.validate(t2)).isNotEmpty();

        ObjectNode t3 = minimalTree();
        t3.put("preamble", "\\documentclass{article}");
        assertThat(validator.validate(t3)).isNotEmpty();
    }

    @Test
    void rejectsDuplicateNodeIds() {
        ObjectNode t = minimalTree();
        ((ArrayNode) t.get("sections")).add(node(mapper, "h", "section")); // same id as the header
        assertThat(validator.validate(t)).anyMatch(p -> p.message().contains("duplicate node id"));
    }

    // ---- assembler ----

    @Test
    void goldenFileForTheStarterTree() throws Exception {
        String actual = new LatexAssembler().assemble(starter.create()).tex();
        Path golden = Path.of("src/test/resources/golden/starter.tex");
        if (Boolean.getBoolean("updateGolden")) Files.writeString(golden, actual);
        assertThat(actual).isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
    }

    @Test
    void assemblyIsDeterministicAndEndsTheDocument() {
        LatexAssembler a = new LatexAssembler();
        String one = a.assemble(starter.create()).tex();
        assertThat(a.assemble(starter.create()).tex()).isEqualTo(one);
        assertThat(one).contains("\\begin{document}").endsWith("\\end{document}\n");
        assertThat(one.indexOf("%%NODE header")).isGreaterThan(one.indexOf("\\begin{document}"));
        assertThat(one.indexOf("%%END footer")).isLessThan(one.indexOf("\\end{document}"));
    }

    @Test
    void everyNodeIsWrappedInMarkersAndLinesMapBackToTheInnermostNode() {
        ResumeTree tree = starter.create();
        AssembledDocument doc = new LatexAssembler().assemble(tree);
        List<ResumeNode> all = new ArrayList<>();
        all.add(tree.header());
        for (ResumeNode s : tree.sections()) { all.add(s); for (ResumeNode c : s.children()) { all.add(c); all.addAll(c.children()); } }
        all.add(tree.footer());
        assertThat(doc.spans()).hasSize(all.size());
        String[] lines = doc.tex().split("\n", -1);
        for (ResumeNode n : all) {
            AssembledDocument.NodeSpan span = doc.spans().stream().filter(x -> x.nodeId().equals(n.id())).findFirst().orElseThrow();
            assertThat(lines[span.startLine() - 1]).isEqualTo("%%NODE " + n.id());
            assertThat(lines[span.endLine() - 1]).isEqualTo("%%END " + n.id());
            assertThat(doc.nodeIdAtLine(doc.firstLineOf(n.id()))).isEqualTo(n.id());
        }
        // a line inside a bullet of the second experience entry maps to the bullet, not to its parents
        ResumeNode bullet = tree.sections().get(1).children().get(1).children().get(2);
        int line = doc.firstLineOf(bullet.id());
        assertThat(lines[line - 1]).contains("Maintain upkeep");
        assertThat(doc.nodeIdAtLine(line)).isEqualTo(bullet.id());
        // a line of the parent's own wrapper text (after the last child) maps to the parent
        ResumeNode exp2 = tree.sections().get(1).children().get(1);
        int endOfList = 0;
        for (int i = 0; i < lines.length; i++) if (lines[i].equals("%%END " + bullet.id())) endOfList = i + 2;
        assertThat(lines[endOfList - 1]).isEqualTo("\\resumeItemListEnd");
        assertThat(doc.nodeIdAtLine(endOfList)).isEqualTo(exp2.id());
        // the locked preamble belongs to no node
        assertThat(doc.nodeIdAtLine(3)).isNull();
    }

    // ---- {{children}} token ----

    static ResumeNode n(String id, NodeType type, String latex, ResumeNode... children) {
        return new ResumeNode(id, type, id, latex, List.of(), List.of(children));
    }

    ResumeTree treeWith(ResumeNode... sections) {
        return new ResumeTree("root", n("h", NodeType.HEADER, "H"), List.of(sections), n("f", NodeType.FOOTER, ""));
    }

    @Test
    void childrenExpandInChildrenOrderAndReorderingNeverEditsLatex() {
        ResumeNode a = n("a", NodeType.SECTION_TEXT, "A-TEXT");
        ResumeNode b = n("b", NodeType.SECTION_TEXT, "B-TEXT");
        ResumeNode c = n("c", NodeType.SECTION_TEXT, "C-TEXT");
        String sec = "\\section{S}\n{{children}}\n\\end-of-S";
        String one = new LatexAssembler().assemble(treeWith(n("s", NodeType.SECTION, sec, a, b, c))).tex();
        String two = new LatexAssembler().assemble(treeWith(n("s", NodeType.SECTION, sec, c, a, b))).tex();
        assertThat(one.indexOf("A-TEXT")).isLessThan(one.indexOf("B-TEXT"));
        assertThat(one.indexOf("B-TEXT")).isLessThan(one.indexOf("C-TEXT"));
        assertThat(two.indexOf("C-TEXT")).isLessThan(two.indexOf("A-TEXT"));
        assertThat(one.indexOf("C-TEXT")).isLessThan(one.indexOf("\\end-of-S"));
        assertThat(one.indexOf("\\section{S}")).isLessThan(one.indexOf("A-TEXT"));
    }

    @Test
    void expansionNestsSectionToSubsectionToBullets() {
        ResumeNode bullet = n("b1", NodeType.SUBSECTION_TEXT, "BULLET");
        ResumeNode sub = n("sub", NodeType.SUBSECTION, "SUB-HEAD\n{{children}}\nSUB-TAIL", bullet);
        ResumeNode sec = n("sec", NodeType.SECTION, "SEC-HEAD\n{{children}}\nSEC-TAIL", sub);
        String tex = new LatexAssembler().assemble(treeWith(sec)).tex();
        assertThat(tex).containsSubsequence("SEC-HEAD", "SUB-HEAD", "BULLET", "SUB-TAIL", "SEC-TAIL");
        assertThat(tex).containsSubsequence("%%NODE sec", "%%NODE sub", "%%NODE b1", "%%END b1", "%%END sub", "%%END sec");
    }

    @Test
    void mixedTextAndSubsectionChildrenExpandInOrderAtTheOneToken() {
        ResumeNode text = n("t", NodeType.SECTION_TEXT, "TEXT-CHILD");
        ResumeNode sub = n("u", NodeType.SUBSECTION, "SUB-CHILD");
        ResumeNode text2 = n("t2", NodeType.SECTION_TEXT, "TEXT-CHILD-2");
        String tex = new LatexAssembler().assemble(treeWith(n("s", NodeType.SECTION, "{{children}}", text, sub, text2))).tex();
        assertThat(tex).containsSubsequence("TEXT-CHILD", "SUB-CHILD", "TEXT-CHILD-2");
    }

    @Test
    void aTokenInAChildlessNodeIsStripped() {
        String tex = new LatexAssembler().assemble(treeWith(n("s", NodeType.SECTION, "BEFORE\n{{children}}\nAFTER"))).tex();
        assertThat(tex).doesNotContain("{{children}}").containsSubsequence("BEFORE", "AFTER");
    }

    @Test
    void aNodeWithChildrenButNoTokenIsRejectedNamingTheNode() {
        ResumeTree tree = treeWith(n("s", NodeType.SECTION, "\\section{S}", n("a", NodeType.SECTION_TEXT, "A")));
        assertThat(validator.validate(mapper.valueToTree(tree)))
                .containsExactly(new app.resume.resumes.TreeProblem("s", "node has children but its latex has no {{children}} token"));
        assertThatThrownBy(() -> new LatexAssembler().assemble(tree)).isInstanceOf(InvalidTreeException.class);
    }

    @Test
    void moreThanOneTokenIsRejectedNamingTheNode() {
        ResumeTree tree = treeWith(n("s", NodeType.SECTION, "{{children}} x {{children}}", n("a", NodeType.SECTION_TEXT, "A")));
        assertThat(validator.validate(mapper.valueToTree(tree)))
                .containsExactly(new app.resume.resumes.TreeProblem("s", "node latex has more than one {{children}} token"));
    }

    @Test
    void anyOtherTokenOrWrongCaseIsRejectedEvenInAChildlessNode() {
        for (String bad : new String[] {"{{items}}", "{{Children}}", "{{ children }}", "{{}}"}) {
            ResumeTree tree = treeWith(n("s", NodeType.SECTION, "x " + bad));
            assertThat(validator.validate(mapper.valueToTree(tree))).as(bad)
                    .anyMatch(p -> p.field().equals("s") && p.message().startsWith("unknown token " + bad));
        }
    }

    @Test
    void theTokenCountsEvenInsideALatexCommentOrVerbatim() {
        ResumeTree commented = treeWith(n("s", NodeType.SECTION, "% {{children}}\n{{children}}", n("a", NodeType.SECTION_TEXT, "A")));
        assertThat(validator.validate(mapper.valueToTree(commented))).anyMatch(p -> p.message().contains("more than one"));
        ResumeTree verbatim = treeWith(n("s", NodeType.SECTION, "\\begin{verbatim}{{children}}\\end{verbatim}", n("a", NodeType.SECTION_TEXT, "A")));
        assertThat(validator.validate(mapper.valueToTree(verbatim))).isEmpty(); // it is the token, wherever it sits
    }

    @Test
    void preambleComesFromTheTemplateResourceOnly() {
        ResumeTree tree = starter.create();
        String standard = new LatexAssembler().assemble(tree).tex();
        String alt = new LatexAssembler(new TemplateResources("template-alt/")).assemble(tree).tex();
        assertThat(standard).contains("\\usepackage{titlesec}").doesNotContain("ALT-PREAMBLE");
        assertThat(alt).contains("ALT-PREAMBLE").doesNotContain("\\usepackage{titlesec}");
        assertThat(alt).isNotEqualTo(standard);
    }

    // ---- the hidden header never leaks ----

    @Test
    void noApiModelExposesTheHiddenHeader() throws Exception {
        String json = mapper.writeValueAsString(starter.create());
        assertThat(json).doesNotContain("documentclass").doesNotContain("newcommand").doesNotContain("usepackage");
        for (Class<?> c : new Class<?>[] {ResumeTree.class, ResumeNode.class}) {
            for (var rc : c.getRecordComponents()) {
                assertThat(rc.getName().toLowerCase()).doesNotContain("preamble").doesNotContain("header_tex");
            }
        }
        // the template and assembler are not wired into any controller
        var scanner = new org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new org.springframework.core.type.filter.AnnotationTypeFilter(RestController.class));
        for (var def : scanner.findCandidateComponents("app.resume")) {
            Class<?> controller = Class.forName(def.getBeanClassName());
            for (var f : controller.getDeclaredFields()) {
                assertThat(f.getType()).isNotIn(TemplateResources.class, LatexAssembler.class);
            }
        }
    }
}
