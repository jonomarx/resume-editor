package app.resume.render;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.resumes.LatexAssembler;
import app.resume.resumes.ResumeTree;
import app.resume.resumes.StarterTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderUnitTests {
    final ObjectMapper mapper = new ObjectMapper();
    final StarterTree starter = new StarterTree(mapper);

    @Test
    void contentHashIsSha256OfTheCanonicalTreeIndependentOfPropertyOrder() throws Exception {
        ResumeTree tree = starter.create();
        String hash = TreeHasher.hash(tree);
        assertThat(hash).matches("[0-9a-f]{64}");
        // the same tree read from JSON with shuffled property order hashes the same
        var json = mapper.valueToTree(tree);
        var shuffled = mapper.createObjectNode();
        List<String> names = new java.util.ArrayList<>();
        json.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        names.forEach(n -> shuffled.set(n, json.get(n)));
        assertThat(TreeHasher.hash(mapper.treeToValue(shuffled, ResumeTree.class))).isEqualTo(hash);
        assertThat(TreeHasher.canonicalJson(tree)).startsWith("{\"footer\":");
    }

    @Test
    void anyChangeToTheTreeChangesTheHash() {
        ResumeTree tree = starter.create();
        var edited = new ResumeTree(tree.id() + "x", tree.header(), tree.sections(), tree.footer());
        assertThat(TreeHasher.hash(edited)).isNotEqualTo(TreeHasher.hash(tree));
    }

    @Test
    void errorMappingHandlesPreambleMultipleAndLinelessErrors() {
        var doc = new LatexAssembler().assemble(starter.create());
        int headerLine = doc.firstLineOf("header");
        String log = "! Undefined control sequence.\nl.3 \\foo\n\n! Missing } inserted.\n<inserted text> }\nl." + headerLine + " \\bar\n\n! Emergency stop.\n";
        var errors = RenderService.mapErrors(log, doc);
        assertThat(errors).hasSize(3);
        assertThat(errors.get(0).nodeId()).isNull(); // line 3 is the locked preamble
        assertThat(errors.get(0).line()).isNull();
        assertThat(errors.get(1).nodeId()).isEqualTo("header");
        assertThat(errors.get(1).line()).isEqualTo(1);
        assertThat(errors.get(2).nodeId()).isNull();
        assertThat(RenderService.mapErrors("garbage", doc)).hasSize(1);
    }

    @Test
    void diskCacheTreatsAnEntryFromAnotherTemplateAsAMiss(@TempDir Path dir) {
        var cache = new DiskRenderCache(dir.toString(), mapper);
        String h = "a".repeat(64);
        cache.store(1, h, "templateA", "%PDF-1".getBytes(), 2, "log");
        assertThat(cache.find(1, h, "templateA")).hasValueSatisfying(e -> assertThat(e.pageCount()).isEqualTo(2));
        assertThat(cache.find(1, h, "templateB")).isEmpty();
        assertThat(cache.find(2, h, "templateA")).isEmpty(); // other user
        assertThat(cache.pdf(2, h)).isEmpty();
        cache.store(1, h, "templateB", "%PDF-2".getBytes(), 1, "log"); // replaced atomically
        assertThat(new String(cache.pdf(1, h).orElseThrow())).isEqualTo("%PDF-2");
        assertThat(cache.find(1, h, "templateB")).isPresent();
    }
}
