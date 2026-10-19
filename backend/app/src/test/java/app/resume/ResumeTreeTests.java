package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.resumes.LatexAssembler;
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
        assertThat(validator.validate(t)).anyMatch(p -> p.contains("duplicate node id"));
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
        assertThat(one.indexOf("%%NODE footer")).isLessThan(one.indexOf("\\end{document}"));
    }

    @Test
    void everyNodeHasAMarkerAndLinesMapBackToTheirNode() {
        ResumeTree tree = starter.create();
        AssembledDocument doc = new LatexAssembler().assemble(tree);
        List<ResumeNode> all = new ArrayList<>();
        all.add(tree.header());
        for (ResumeNode s : tree.sections()) { all.add(s); for (ResumeNode c : s.children()) { all.add(c); all.addAll(c.children()); } }
        all.add(tree.footer());
        assertThat(doc.nodeStarts()).hasSize(all.size());
        String[] lines = doc.tex().split("\n", -1);
        for (ResumeNode n : all) {
            int marker = doc.firstLineOf(n.id()) - 1;
            assertThat(lines[marker - 1]).isEqualTo("%%NODE " + n.id());
            // the line holding the node's own LaTeX maps back to it
            assertThat(doc.nodeIdAtLine(doc.firstLineOf(n.id()))).isEqualTo(n.id());
        }
        // a line inside a bullet of the second experience entry
        ResumeNode bullet = tree.sections().get(1).children().get(1).children().get(2);
        int line = doc.firstLineOf(bullet.id());
        assertThat(lines[line - 1]).contains("Maintain upkeep");
        assertThat(doc.nodeIdAtLine(line)).isEqualTo(bullet.id());
        // the locked preamble belongs to no node
        assertThat(doc.nodeIdAtLine(3)).isNull();
    }

    @Test
    void preambleComesFromTheTemplateResourceOnly() {
        ResumeTree tree = starter.create();
        String standard = new LatexAssembler().assemble(tree).tex();
        String alt = new LatexAssembler(new TemplateResources("template-alt/")).assemble(tree).tex();
        assertThat(standard).contains("\\usepackage{titlesec}").doesNotContain("ALT-PREAMBLE");
        assertThat(alt).contains("ALT-PREAMBLE").doesNotContain("\\usepackage{titlesec}")
                .contains("ALT-SECTION{Education}");
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
