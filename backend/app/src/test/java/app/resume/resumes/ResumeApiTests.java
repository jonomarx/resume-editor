package app.resume.resumes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import app.resume.CsrfSupport;
import app.resume.render.TreeHasher;
import app.resume.storage.FileState;
import app.resume.storage.FileStorage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ResumeApiTests {
    @Autowired MockMvc mvc;
    @Autowired StarterTree starter;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired FileStorage storage;

    Cookie newUser() throws Exception {
        String name = "m" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + name + "\",\"password\":\"correct-horse-battery\"}"))
                .andReturn().getResponse().getCookie("SESSION");
    }

    JsonNode getMaster(Cookie s) throws Exception {
        return mapper.readTree(mvc.perform(get("/api/resumes/master").cookie(s)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    /** The tree JSON with the first bullet of the first experience entry's latex replaced. */
    ObjectNode treeWithBullet(JsonNode tree, String latex) {
        ObjectNode copy = tree.deepCopy();
        ((ObjectNode) copy.get("sections").get(1).get("children").get(0).get("children").get(0)).put("latex", latex);
        return copy;
    }

    ResultActions put(Cookie s, long base, JsonNode tree) throws Exception {
        ObjectNode body = mapper.createObjectNode().put("baseVersion", base);
        body.set("tree", tree);
        return mvc.perform(CsrfSupport.put("/api/resumes/master").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(body)));
    }

    ResultActions restore(Cookie s, long version, String body) throws Exception {
        return mvc.perform(CsrfSupport.post("/api/resumes/master/versions/" + version + "/restore").cookie(s)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void firstReadCreatesTheMasterFromTheStarterTreeAsVersionOne() throws Exception {
        Cookie s = newUser();
        JsonNode doc = getMaster(s);
        assertThat(doc.get("version").asLong()).isEqualTo(1);
        assertThat(doc.get("contentHash").asText()).isEqualTo(TreeHasher.hash(starter.create()));
        assertThat(doc.get("tree")).isEqualTo(mapper.valueToTree(starter.create()));
        assertThat(doc.get("updatedAt").asText()).isNotBlank();
        assertThat(getMaster(s).get("id")).isEqualTo(doc.get("id")); // same resume on the second read
        mvc.perform(get("/api/resumes/master/versions").cookie(s)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].version").value(1));
    }

    @Test
    void saveCreatesTheNextVersionAndHistoryIsNewestFirst() throws Exception {
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        put(s, 1, treeWithBullet(v1.get("tree"), "\\resumeItem{edited}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.id").value(v1.get("id").asLong()));
        JsonNode v2 = getMaster(s);
        assertThat(v2.get("contentHash").asText()).isNotEqualTo(v1.get("contentHash").asText());
        assertThat(v2.get("tree").get("sections").get(1).get("children").get(0).get("children").get(0).get("latex").asText())
                .isEqualTo("\\resumeItem{edited}");
        mvc.perform(get("/api/resumes/master/versions").cookie(s)).andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].version").value(2)).andExpect(jsonPath("$[1].version").value(1))
                .andExpect(jsonPath("$[0].contentHash").value(v2.get("contentHash").asText()))
                .andExpect(jsonPath("$[0].createdAt").isString());
        mvc.perform(get("/api/resumes/master/versions/1").cookie(s)).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.contentHash").value(v1.get("contentHash").asText()));
    }

    @Test
    void aStaleBaseVersionIs409WithTheCurrentVersionAndNothingIsWritten() throws Exception {
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        put(s, 1, treeWithBullet(v1.get("tree"), "\\resumeItem{a}")).andExpect(status().isOk());
        put(s, 1, treeWithBullet(v1.get("tree"), "\\resumeItem{b}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("version_conflict")).andExpect(jsonPath("$.currentVersion").value(2))
                .andExpect(jsonPath("$.message").isString());
        put(s, 5, v1.get("tree")).andExpect(status().isConflict()).andExpect(jsonPath("$.currentVersion").value(2));
        mvc.perform(get("/api/resumes/master/versions").cookie(s)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void savingIdenticalContentCreatesNoVersionAndReturnsTheCurrentDocument() throws Exception {
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        put(s, 1, v1.get("tree")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.contentHash").value(v1.get("contentHash").asText()));
        mvc.perform(get("/api/resumes/master/versions").cookie(s)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void tagsMustBeVocabularySlugsAndEachUnknownSlugIsNamed() throws Exception {
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        mvc.perform(CsrfSupport.post("/api/tags").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Java\",\"category\":\"language\"}")).andExpect(status().isCreated());
        mvc.perform(CsrfSupport.post("/api/tags/java/aliases").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"alias\":\"JDK\"}")).andExpect(status().isOk());

        ObjectNode ok = treeWithBullet(v1.get("tree"), "\\resumeItem{x}");
        ((com.fasterxml.jackson.databind.node.ArrayNode) ok.get("sections").get(1).get("children").get(0).get("children").get(0).get("tags")).add("java");
        put(s, 1, ok).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));

        ObjectNode bad = ok.deepCopy();
        var bullet = (ObjectNode) bad.get("sections").get(1).get("children").get(0).get("children").get(0);
        ((com.fasterxml.jackson.databind.node.ArrayNode) bullet.get("tags")).add("javscript").add("jdk");
        put(s, 2, bad).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.details.length()").value(2))
                .andExpect(jsonPath("$.details[0].field").value(bullet.get("id").asText()))
                .andExpect(jsonPath("$.details[0].message").value("unknown tag slug \"javscript\""))
                .andExpect(jsonPath("$.details[1].message").value("unknown tag slug \"jdk\"")); // an alias is not a slug
        mvc.perform(get("/api/resumes/master/versions").cookie(s)).andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void invalidTreesAndBodiesAre400ValidationFailed() throws Exception {
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        ObjectNode noToken = v1.get("tree").deepCopy();
        ((ObjectNode) noToken.get("sections").get(0)).put("latex", "\\section{Education}");
        put(s, 1, noToken).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.details[0].field").value("sec-education"))
                .andExpect(jsonPath("$.details[0].message").value("node has children but its latex has no {{children}} token"));
        mvc.perform(CsrfSupport.put("/api/resumes/master").cookie(s).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.details.length()").value(2));
        for (String base : new String[] {"0", "-1", "\"2\"", "1.5", "null"}) {
            mvc.perform(CsrfSupport.put("/api/resumes/master").cookie(s).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"baseVersion\":" + base + ",\"tree\":" + v1.get("tree") + "}")).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/resumes/master/versions").cookie(s)).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void restoreCopiesAnOldVersionIntoANewOneAndNeverRewritesHistory() throws Exception {
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        put(s, 1, treeWithBullet(v1.get("tree"), "\\resumeItem{two}")).andExpect(status().isOk());
        put(s, 2, treeWithBullet(v1.get("tree"), "\\resumeItem{three}")).andExpect(status().isOk());
        restore(s, 1, "{\"baseVersion\":3}").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(4))
                .andExpect(jsonPath("$.contentHash").value(v1.get("contentHash").asText()));
        assertThat(getMaster(s).get("tree")).isEqualTo(v1.get("tree"));
        mvc.perform(get("/api/resumes/master/versions").cookie(s)).andExpect(jsonPath("$.length()").value(4));
        mvc.perform(get("/api/resumes/master/versions/3").cookie(s)).andExpect(jsonPath("$.tree.sections[1].children[0].children[0].latex")
                .value("\\resumeItem{three}")); // history intact
    }

    @Test
    void restoreRules409IdenticalNoOp400And404() throws Exception {
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        put(s, 1, treeWithBullet(v1.get("tree"), "\\resumeItem{two}")).andExpect(status().isOk());
        restore(s, 1, "{\"baseVersion\":1}").andExpect(status().isConflict()).andExpect(jsonPath("$.currentVersion").value(2));
        restore(s, 2, "{\"baseVersion\":2}").andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2)); // equals current
        mvc.perform(get("/api/resumes/master/versions").cookie(s)).andExpect(jsonPath("$.length()").value(2));
        restore(s, 99, "{\"baseVersion\":2}").andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
        restore(s, 1, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        restore(s, 1, "{\"baseVersion\":0}").andExpect(status().isBadRequest());
        mvc.perform(get("/api/resumes/master/versions/99").cookie(s)).andExpect(status().isNotFound());
    }

    @Test
    void restoreRejectsAStoredTreeThatBreaksTodaysRulesLikeASaveDoes() throws Exception {
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        mvc.perform(CsrfSupport.post("/api/tags").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Temp\",\"category\":\"domain\"}")).andExpect(status().isCreated());
        ObjectNode tagged = treeWithBullet(v1.get("tree"), "\\resumeItem{t}");
        ((com.fasterxml.jackson.databind.node.ArrayNode) tagged.get("sections").get(1).get("children").get(0).get("children").get(0).get("tags")).add("temp");
        put(s, 1, tagged).andExpect(status().isOk());
        put(s, 2, v1.get("tree")).andExpect(status().isOk()); // version 3 without the tag
        jdbc.update("delete from tag_key where tag_key = 'temp'");
        jdbc.update("delete from tag where slug = 'temp'"); // the vocabulary no longer has the slug version 2 uses
        restore(s, 2, "{\"baseVersion\":3}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.details[0].message").value("unknown tag slug \"temp\""));
    }

    @Test
    void everyUserHasTheirOwnMasterAndFilesAreCommittedInTheRegistry() throws Exception {
        Cookie a = newUser();
        Cookie b = newUser();
        JsonNode da = getMaster(a);
        JsonNode db = getMaster(b);
        assertThat(da.get("id")).isNotEqualTo(db.get("id"));
        put(a, 1, treeWithBullet(da.get("tree"), "\\resumeItem{only a}")).andExpect(status().isOk());
        assertThat(getMaster(b).get("version").asLong()).isEqualTo(1);
        long uidA = jdbc.queryForObject("select user_id from resume where id = ?", Long.class, da.get("id").asLong());
        var files = storage.list("users/" + uidA + "/resumes/" + da.get("id").asLong() + "/", FileState.COMMITTED);
        assertThat(files).extracting(f -> f.key().substring(f.key().lastIndexOf('/') + 1)).containsExactly("v1.json", "v2.json");
        assertThat(storage.list("users/" + uidA + "/resumes/", FileState.PENDING)).isEmpty();
        byte[] stored = storage.get(files.get(1).key()).orElseThrow();
        assertThat(mapper.readTree(stored).get("sections").get(1).get("children").get(0).get("children").get(0).get("latex").asText())
                .isEqualTo("\\resumeItem{only a}");
    }

    @Test
    void endpointsRequireSignInAndCsrfOnWrites() throws Exception {
        mvc.perform(get("/api/resumes/master")).andExpect(status().isUnauthorized());
        Cookie s = newUser();
        JsonNode v1 = getMaster(s);
        mvc.perform(MockMvcRequestBuilders.put("/api/resumes/master").cookie(s).contentType(MediaType.APPLICATION_JSON)
                .content("{\"baseVersion\":1,\"tree\":" + v1.get("tree") + "}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        mvc.perform(MockMvcRequestBuilders.post("/api/resumes/master/versions/1/restore").cookie(s)
                .contentType(MediaType.APPLICATION_JSON).content("{\"baseVersion\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("csrf_invalid"));
        mvc.perform(get("/api/resumes/master/versions")).andExpect(status().isUnauthorized());
    }
}
