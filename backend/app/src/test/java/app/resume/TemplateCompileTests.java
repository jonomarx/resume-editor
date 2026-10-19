package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.resumes.AssembledDocument;
import app.resume.resumes.LatexAssembler;
import app.resume.resumes.ResumeNode;
import app.resume.resumes.ResumeTree;
import app.resume.resumes.StarterTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/** Slow Docker loop: the assembled starter document compiles in the real renderer container (US-012) to one page. */
@Tag("postgres")
class TemplateCompileTests {
    static GenericContainer<?> renderer;
    final ObjectMapper mapper = new ObjectMapper();
    final LatexAssembler assembler = new LatexAssembler();

    @BeforeAll
    static void startRenderer() {
        Path dir = Path.of("../../renderer").toAbsolutePath().normalize();
        renderer = new GenericContainer<>(new ImageFromDockerfile("resume-renderer-test", false).withFileFromPath(".", dir))
                .withExposedPorts(8080)
                .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withReadonlyRootfs(true)
                        .withTmpFs(Map.of("/work", "rw,size=64m,uid=10001")))
                .waitingFor(Wait.forHttp("/health").forPort(8080));
        renderer.start();
    }

    @AfterAll
    static void stop() {
        if (renderer != null) renderer.stop();
    }

    HttpResponse<byte[]> render(String tex) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://" + renderer.getHost() + ":" + renderer.getMappedPort(8080) + "/render"))
                .POST(HttpRequest.BodyPublishers.ofString(tex)).build();
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void starterTreeRendersToExactlyOnePage() throws Exception {
        ResumeTree tree = new StarterTree(mapper).create();
        HttpResponse<byte[]> res = render(assembler.assemble(tree).tex());
        assertThat(res.statusCode()).as(new String(res.body(), StandardCharsets.UTF_8)).isEqualTo(200);
        assertThat(res.headers().firstValue("X-Page-Count")).contains("1");
        assertThat(new String(res.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void aCompileErrorLineMapsBackToTheOffendingNode() throws Exception {
        ResumeTree tree = new StarterTree(mapper).create();
        ResumeNode bullet = tree.sections().get(1).children().get(0).children().get(0);
        ResumeNode broken = new ResumeNode(bullet.id(), bullet.type(), bullet.title(), "\\resumeItem{\\thisMacroDoesNotExist}",
                bullet.tags(), bullet.children());
        ResumeNode exp1 = tree.sections().get(1).children().get(0);
        ResumeNode newExp1 = new ResumeNode(exp1.id(), exp1.type(), exp1.title(), exp1.latex(), exp1.tags(),
                List.of(broken, exp1.children().get(1), exp1.children().get(2)));
        ResumeNode exp = tree.sections().get(1);
        List<ResumeNode> children = new java.util.ArrayList<>(exp.children());
        children.set(0, newExp1);
        ResumeNode newExp = new ResumeNode(exp.id(), exp.type(), exp.title(), exp.latex(), exp.tags(), children);
        List<ResumeNode> sections = new java.util.ArrayList<>(tree.sections());
        sections.set(1, newExp);
        AssembledDocument doc = assembler.assemble(new ResumeTree(tree.id(), tree.header(), sections, tree.footer()));

        HttpResponse<byte[]> res = render(doc.tex());
        assertThat(res.statusCode()).isEqualTo(422);
        Matcher m = Pattern.compile("l\\.(\\d+)").matcher(mapper.readTree(res.body()).get("log").asText());
        assertThat(m.find()).as("log names a line").isTrue();
        assertThat(doc.nodeIdAtLine(Integer.parseInt(m.group(1)))).isEqualTo(bullet.id());
    }
}
