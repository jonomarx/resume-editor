package app.resume.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.CsrfSupport;
import app.resume.resumes.NodeType;
import app.resume.resumes.ResumeNode;
import app.resume.resumes.ResumeTree;
import app.resume.resumes.StarterTree;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RenderTests {
    @Autowired MockMvc mvc;
    @Autowired StarterTree starter;
    @Autowired ObjectMapper mapper;

    Cookie newUser() throws Exception {
        String name = "r" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
    }

    ResultActions render(Cookie s, ResumeTree tree) throws Exception {
        return mvc.perform(CsrfSupport.post("/api/render").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(java.util.Map.of("tree", tree))));
    }

    /** The starter tree with one bullet's latex replaced. */
    ResumeTree withBullet(String latex) {
        ResumeTree t = starter.create();
        List<ResumeNode> sections = new ArrayList<>(t.sections());
        ResumeNode exp = sections.get(1);
        List<ResumeNode> subs = new ArrayList<>(exp.children());
        ResumeNode sub = subs.get(0);
        List<ResumeNode> bullets = new ArrayList<>(sub.children());
        ResumeNode b = bullets.get(0);
        bullets.set(0, new ResumeNode(b.id(), b.type(), b.title(), latex, b.tags(), b.children()));
        subs.set(0, new ResumeNode(sub.id(), sub.type(), sub.title(), sub.latex(), sub.tags(), bullets));
        sections.set(1, new ResumeNode(exp.id(), exp.type(), exp.title(), exp.latex(), exp.tags(), subs));
        return new ResumeTree(t.id(), t.header(), sections, t.footer());
    }

    @Test
    void firstRenderIsAMissThenTheSameTreeIsAHitWithoutCallingTheRenderer() throws Exception {
        Cookie s = newUser();
        ResumeTree tree = starter.create();
        int before = FakeRendererConfig.CALLS.get();
        String hash = TreeHasher.hash(tree);
        render(s, tree).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.cached").value(false)).andExpect(jsonPath("$.contentHash").value(hash))
                .andExpect(jsonPath("$.pageCount").value(1)).andExpect(jsonPath("$.pdfUrl").value("/api/render/" + hash + "/pdf"))
                .andExpect(jsonPath("$.errors.length()").value(0)).andExpect(jsonPath("$.log").value("fake log"));
        render(s, tree).andExpect(status().isOk()).andExpect(jsonPath("$.cached").value(true))
                .andExpect(jsonPath("$.contentHash").value(hash)).andExpect(jsonPath("$.pageCount").value(1));
        assertThat(FakeRendererConfig.CALLS.get() - before).isEqualTo(1);
        // a changed tree is a different hash and a new render
        render(s, withBullet("\\resumeItem{changed}")).andExpect(jsonPath("$.cached").value(false))
                .andExpect(jsonPath("$.contentHash").value(org.hamcrest.Matchers.not(hash)));
        assertThat(FakeRendererConfig.CALLS.get() - before).isEqualTo(2);
    }

    @Test
    void theCachePdfIsServedWithPdfContentType() throws Exception {
        Cookie s = newUser();
        String hash = TreeHasher.hash(starter.create());
        render(s, starter.create()).andExpect(status().isOk());
        var res = mvc.perform(get("/api/render/" + hash + "/pdf").cookie(s)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(res.getContentType()).isEqualTo("application/pdf");
        assertThat(new String(res.getContentAsByteArray())).startsWith("%PDF-");
    }

    @Test
    void pageCountComesFromTheRenderer() throws Exception {
        Cookie s = newUser();
        render(s, withBullet("\\resumeItem{x}\\newpage\\newpage")).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.pageCount").value(3));
    }

    @Test
    void aCompileErrorIsA200WithTheErrorMappedToTheOffendingNode() throws Exception {
        Cookie s = newUser();
        ResumeTree broken = withBullet("\\resumeItem{ok}\n\\boom");
        String bulletId = broken.sections().get(1).children().get(0).children().get(0).id();
        render(s, broken).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.pageCount").doesNotExist()).andExpect(jsonPath("$.pdfUrl").doesNotExist())
                .andExpect(jsonPath("$.errors.length()").value(1))
                .andExpect(jsonPath("$.errors[0].nodeId").value(bulletId))
                .andExpect(jsonPath("$.errors[0].line").value(2)) // second line of that node's own LaTeX
                .andExpect(jsonPath("$.errors[0].message").value("Undefined control sequence"))
                .andExpect(jsonPath("$.contentHash").value(TreeHasher.hash(broken)));
        // failures are not cached as PDFs
        mvc.perform(get("/api/render/" + TreeHasher.hash(broken) + "/pdf").cookie(s)).andExpect(status().isNotFound());
    }

    @Test
    void anErrorInAParentWrapperIsAttributedToTheParent() throws Exception {
        Cookie s = newUser();
        ResumeTree t = starter.create();
        List<ResumeNode> sections = new ArrayList<>(t.sections());
        ResumeNode edu = sections.get(0);
        sections.set(0, new ResumeNode(edu.id(), edu.type(), edu.title(), edu.latex() + "\n\\boom", edu.tags(), edu.children()));
        render(s, new ResumeTree(t.id(), t.header(), sections, t.footer())).andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0].nodeId").value(edu.id()));
    }

    @Test
    void anInvalidTreeIs400ValidationFailedWithTheNodeIdAndNothingIsRendered() throws Exception {
        Cookie s = newUser();
        int before = FakeRendererConfig.CALLS.get();
        ResumeTree t = starter.create();
        List<ResumeNode> sections = new ArrayList<>(t.sections());
        ResumeNode edu = sections.get(0);
        sections.set(0, new ResumeNode(edu.id(), edu.type(), edu.title(), "\\section{Education}", edu.tags(), edu.children()));
        render(s, new ResumeTree(t.id(), t.header(), sections, t.footer())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.details[0].field").value(edu.id()))
                .andExpect(jsonPath("$.details[0].message").value("node has children but its latex has no {{children}} token"));
        mvc.perform(CsrfSupport.post("/api/render").cookie(s).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(CsrfSupport.post("/api/render").cookie(s).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tree\":{\"id\":\"x\"}}")).andExpect(status().isBadRequest());
        assertThat(FakeRendererConfig.CALLS.get()).isEqualTo(before);
    }

    @Test
    void anotherUsersPdfIsA404NeverA403AndCachesAreScopedPerUser() throws Exception {
        Cookie a = newUser();
        Cookie b = newUser();
        ResumeTree tree = starter.create();
        String hash = TreeHasher.hash(tree);
        render(a, tree).andExpect(jsonPath("$.cached").value(false));
        mvc.perform(get("/api/render/" + hash + "/pdf").cookie(a)).andExpect(status().isOk());
        mvc.perform(get("/api/render/" + hash + "/pdf").cookie(b)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        render(b, tree).andExpect(jsonPath("$.cached").value(false)); // B does not get A's cached render
        mvc.perform(get("/api/render/" + hash + "/pdf").cookie(b)).andExpect(status().isOk());
        for (String bad : new String[] {"abc", "A".repeat(64), "0".repeat(64), hash.substring(1)}) {
            mvc.perform(get("/api/render/" + bad + "/pdf").cookie(a)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/api/render/" + hash + "/pdf")).andExpect(status().isUnauthorized());
    }

    @Test
    void renderRequiresSignInAndTheCsrfHeader() throws Exception {
        Cookie s = newUser();
        String body = mapper.writeValueAsString(java.util.Map.of("tree", starter.create()));
        mvc.perform(MockMvcRequestBuilders.post("/api/render").cookie(s).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        mvc.perform(CsrfSupport.post("/api/render").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnavailableRendererIs503AndIsNotCached() throws Exception {
        Cookie s = newUser();
        ResumeTree t = withBullet("\\resumeItem{\\unavailable}");
        render(s, t).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("renderer_unavailable"));
        mvc.perform(get("/api/render/" + TreeHasher.hash(t) + "/pdf").cookie(s)).andExpect(status().isNotFound());
    }
}
