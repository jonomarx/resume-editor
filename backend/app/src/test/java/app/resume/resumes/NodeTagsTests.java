package app.resume.resumes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.CsrfSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
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

/** Node tags: only vocabulary slugs are accepted, and resume_node_tag always matches the current tree. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NodeTagsTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired NodeTagIndex index;

    Cookie newUser() throws Exception {
        String name = "n" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\"}")).andReturn().getResponse().getCookie("SESSION");
    }

    void tag(Cookie s, String name) throws Exception {
        mvc.perform(CsrfSupport.post("/api/tags").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"category\":\"language\"}")).andExpect(status().is2xxSuccessful());
    }

    JsonNode master(Cookie s) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/resumes/master").cookie(s)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    /** Sets the tags of the first bullet (b1) and of the education entry (edu1) of the starter tree. */
    ObjectNode withTags(JsonNode tree, List<String> bulletTags, List<String> eduTags) {
        ObjectNode copy = tree.deepCopy();
        setTags((ObjectNode) copy.get("sections").get(1).get("children").get(0).get("children").get(0), bulletTags);
        setTags((ObjectNode) copy.get("sections").get(0).get("children").get(0), eduTags);
        return copy;
    }

    void setTags(ObjectNode node, List<String> tags) {
        ArrayNode arr = node.putArray("tags");
        tags.forEach(arr::add);
    }

    ResultActions put(Cookie s, long base, JsonNode tree) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("baseVersion", base);
        body.set("tree", tree);
        return mvc.perform(CsrfSupport.put("/api/resumes/master").cookie(s).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }

    List<NodeTagIndex.Entry> entries(Cookie s) throws Exception {
        return index.entries(master(s).get("id").asLong());
    }

    @Test
    void tagsAreSavedWithTheTreeReturnedOnReadAndIndexed() throws Exception {
        Cookie s = newUser();
        tag(s, "Java");
        tag(s, "Python");
        JsonNode v1 = master(s);
        assertThat(entries(s)).isEmpty();
        put(s, 1, withTags(v1.get("tree"), List.of("java", "python"), List.of("python"))).andExpect(status().isOk());
        JsonNode v2 = master(s);
        assertThat(v2.get("tree").get("sections").get(1).get("children").get(0).get("children").get(0).get("tags")).hasSize(2);
        assertThat(entries(s)).containsExactly(new NodeTagIndex.Entry("edu-1", "python"),
                new NodeTagIndex.Entry("exp-1-b1", "java"), new NodeTagIndex.Entry("exp-1-b1", "python"));
        assertThat(index.nodesWithTag(userOf(s), v2.get("id").asLong(), "python")).containsExactly("edu-1", "exp-1-b1");
    }

    long userOf(Cookie s) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/me").cookie(s)).andReturn().getResponse().getContentAsString()).get("id").asLong();
    }

    @Test
    void theIndexFollowsEachSaveAndDuplicateTagsOnANodeCollapse() throws Exception {
        Cookie s = newUser();
        tag(s, "Java");
        tag(s, "Rust");
        JsonNode v1 = master(s);
        put(s, 1, withTags(v1.get("tree"), List.of("java", "java"), List.of())).andExpect(status().isOk());
        assertThat(entries(s)).containsExactly(new NodeTagIndex.Entry("exp-1-b1", "java"));
        put(s, 2, withTags(v1.get("tree"), List.of("rust"), List.of("java"))).andExpect(status().isOk());
        assertThat(entries(s)).containsExactly(new NodeTagIndex.Entry("edu-1", "java"), new NodeTagIndex.Entry("exp-1-b1", "rust"));
        put(s, 3, v1.get("tree")).andExpect(status().isOk()); // all tags removed
        assertThat(entries(s)).isEmpty();
    }

    @Test
    void anIdenticalSaveLeavesTheIndexUntouched() throws Exception {
        Cookie s = newUser();
        tag(s, "Java");
        JsonNode v1 = master(s);
        ObjectNode tagged = withTags(v1.get("tree"), List.of("java"), List.of());
        put(s, 1, tagged).andExpect(status().isOk());
        put(s, 2, tagged).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
        assertThat(entries(s)).containsExactly(new NodeTagIndex.Entry("exp-1-b1", "java"));
    }

    @Test
    void freeTextAndUnknownSlugsAreRejectedAndTheIndexIsUnchanged() throws Exception {
        Cookie s = newUser();
        tag(s, "Java");
        JsonNode v1 = master(s);
        put(s, 1, withTags(v1.get("tree"), List.of("java"), List.of())).andExpect(status().isOk());
        for (String bad : new String[] {"Java", "C++", "free text", "javscript", ""}) {
            put(s, 2, withTags(v1.get("tree"), List.of("java", bad), List.of())).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_failed"))
                    .andExpect(jsonPath("$.details[0].field").value("exp-1-b1"));
        }
        assertThat(master(s).get("version").asLong()).isEqualTo(2);
        assertThat(entries(s)).containsExactly(new NodeTagIndex.Entry("exp-1-b1", "java"));
    }

    @Test
    void anAliasIsNotAcceptedAsANodeTag() throws Exception {
        Cookie s = newUser();
        tag(s, "Kubernetes");
        mvc.perform(CsrfSupport.post("/api/tags/kubernetes/aliases").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"k8s\"}")).andExpect(status().isOk());
        JsonNode v1 = master(s);
        put(s, 1, withTags(v1.get("tree"), List.of("k8s"), List.of())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[0].message").value("unknown tag slug \"k8s\""));
        put(s, 1, withTags(v1.get("tree"), List.of("kubernetes"), List.of())).andExpect(status().isOk());
        assertThat(entries(s)).containsExactly(new NodeTagIndex.Entry("exp-1-b1", "kubernetes"));
    }

    @Test
    void restoreRebuildsTheIndexFromTheOldVersion() throws Exception {
        Cookie s = newUser();
        tag(s, "Java");
        tag(s, "Rust");
        JsonNode v1 = master(s);
        put(s, 1, withTags(v1.get("tree"), List.of("java"), List.of("java"))).andExpect(status().isOk());       // v2
        put(s, 2, withTags(v1.get("tree"), List.of("rust"), List.of())).andExpect(status().isOk());              // v3
        assertThat(entries(s)).containsExactly(new NodeTagIndex.Entry("exp-1-b1", "rust"));
        mvc.perform(CsrfSupport.post("/api/resumes/master/versions/2/restore").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseVersion\":3}")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4));
        assertThat(entries(s)).containsExactly(new NodeTagIndex.Entry("edu-1", "java"), new NodeTagIndex.Entry("exp-1-b1", "java"));
    }

    @Test
    void theIndexIsPerUserEvenForTheSameSlug() throws Exception {
        Cookie a = newUser();
        Cookie b = newUser();
        tag(a, "Java");
        tag(b, "Java");
        put(a, 1, withTags(master(a).get("tree"), List.of("java"), List.of())).andExpect(status().isOk());
        assertThat(entries(a)).hasSize(1);
        assertThat(entries(b)).isEmpty();
        put(b, 1, withTags(master(b).get("tree"), List.of("java"), List.of("java"))).andExpect(status().isOk());
        assertThat(entries(a)).hasSize(1);
        assertThat(entries(b)).hasSize(2);
        // a slug only A has is unknown to B
        tag(a, "OnlyA");
        put(b, 2, withTags(master(b).get("tree"), List.of("onlya"), List.of())).andExpect(status().isBadRequest());
        assertThat(index.nodesWithTag(userOf(b), master(b).get("id").asLong(), "java")).containsExactly("edu-1", "exp-1-b1");
        assertThat(index.nodesWithTag(userOf(a), master(b).get("id").asLong(), "java")).isEmpty(); // not A's resume
    }
}
