package app.resume.render;

import static org.assertj.core.api.Assertions.assertThat;

import app.resume.PostgresContainerSupport;
import app.resume.PostgresTest;
import app.resume.resumes.AssembledDocument;
import app.resume.resumes.LatexAssembler;
import app.resume.resumes.ResumeNode;
import app.resume.resumes.ResumeTree;
import app.resume.resumes.StarterTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;

/** Slow loop: the real HTTP client against the real renderer container (US-012), through RenderService. */
@PostgresTest
class RenderContainerPostgresTests extends PostgresContainerSupport {
    static final GenericContainer<?> RENDERER = new GenericContainer<>(
            new ImageFromDockerfile("resume-renderer-test", false).withFileFromPath(".", Path.of("../../renderer").toAbsolutePath().normalize()))
            .withExposedPorts(8080)
            .withCreateContainerCmdModifier(cmd -> cmd.getHostConfig().withReadonlyRootfs(true)
                    .withTmpFs(Map.of("/work", "rw,size=64m,uid=10001")))
            .waitingFor(Wait.forHttp("/health").forPort(8080));

    static {
        RENDERER.start();
    }

    @DynamicPropertySource
    static void rendererUrl(DynamicPropertyRegistry registry) {
        registry.add("app.renderer.url", () -> "http://" + RENDERER.getHost() + ":" + RENDERER.getMappedPort(8080));
    }

    @Autowired RenderService service;
    @Autowired StarterTree starter;

    @Test
    void starterRendersToOnePageThenIsServedFromTheCache() {
        ResumeTree tree = starter.create();
        var first = service.render(9001, tree);
        assertThat(first).isInstanceOfSatisfying(RenderService.Rendered.class, r -> {
            assertThat(r.cached()).isFalse();
            assertThat(r.pageCount()).isEqualTo(1);
        });
        assertThat(service.render(9001, tree)).isInstanceOfSatisfying(RenderService.Rendered.class, r -> assertThat(r.cached()).isTrue());
    }

    @Test
    void aRealCompileErrorMapsToTheBrokenBullet() {
        ResumeTree t = starter.create();
        List<ResumeNode> sections = new ArrayList<>(t.sections());
        ResumeNode exp = sections.get(1);
        List<ResumeNode> subs = new ArrayList<>(exp.children());
        ResumeNode sub = subs.get(0);
        List<ResumeNode> bullets = new ArrayList<>(sub.children());
        ResumeNode b = bullets.get(1);
        bullets.set(1, new ResumeNode(b.id(), b.type(), b.title(), "\\resumeItem{\\thisMacroDoesNotExist}", b.tags(), b.children()));
        subs.set(0, new ResumeNode(sub.id(), sub.type(), sub.title(), sub.latex(), sub.tags(), bullets));
        sections.set(1, new ResumeNode(exp.id(), exp.type(), exp.title(), exp.latex(), exp.tags(), subs));
        var result = service.render(9002, new ResumeTree(t.id(), t.header(), sections, t.footer()));
        assertThat(result).isInstanceOfSatisfying(RenderService.Failed.class, f -> {
            assertThat(f.errors()).isNotEmpty();
            assertThat(f.errors().get(0).nodeId()).isEqualTo(b.id());
            assertThat(f.errors().get(0).line()).isEqualTo(1);
            assertThat(f.errors().get(0).message()).contains("Undefined control sequence");
        });
    }
}
