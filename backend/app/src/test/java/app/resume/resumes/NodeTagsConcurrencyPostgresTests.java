package app.resume.resumes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import app.resume.CsrfSupport;
import app.resume.PostgresContainerSupport;
import app.resume.PostgresTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/** Writers retrying on 409 until they win: the index must end up exactly equal to the final tree's tags. */
@PostgresTest
@AutoConfigureMockMvc
class NodeTagsConcurrencyPostgresTests extends PostgresContainerSupport {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired NodeTagIndex index;

    @Test
    void concurrentSavesLeaveAConsistentIndex() throws Exception {
        Cookie s = mvc.perform(CsrfSupport.post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"tagindexracer\",\"password\":\"correct-horse-battery\"}")).andReturn().getResponse().getCookie("SESSION");
        int n = 8;
        for (int i = 0; i < n; i++) {
            mvc.perform(CsrfSupport.post("/api/tags").cookie(s).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Tag" + i + "\",\"category\":\"domain\"}")).andReturn();
        }
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int w = i;
            fs.add(pool.submit(() -> {
                go.await();
                int conflicts = 0;
                while (true) { // read the current version, save a tree tagged only with this writer's tag, retry on 409
                    JsonNode doc = mapper.readTree(mvc.perform(get("/api/resumes/master").cookie(s)).andReturn().getResponse().getContentAsString());
                    ObjectNode tree = doc.get("tree").deepCopy();
                    var bullet = (ObjectNode) tree.get("sections").get(1).get("children").get(0).get("children").get(0);
                    bullet.put("latex", "\\resumeItem{writer " + w + "}");
                    bullet.putArray("tags").add("tag" + w);
                    ((ObjectNode) tree.get("sections").get(0).get("children").get(0)).putArray("tags").add("tag" + w);
                    ObjectNode body = mapper.createObjectNode().put("baseVersion", doc.get("version").asLong());
                    body.set("tree", tree);
                    int st = mvc.perform(CsrfSupport.put("/api/resumes/master").cookie(s).contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(body))).andReturn().getResponse().getStatus();
                    if (st == 200) return conflicts;
                    assertThat(st).isEqualTo(409);
                    conflicts++;
                }
            }));
        }
        go.countDown();
        for (Future<Integer> f : fs) f.get();
        pool.shutdown();

        JsonNode fin = mapper.readTree(mvc.perform(get("/api/resumes/master").cookie(s)).andReturn().getResponse().getContentAsString());
        assertThat(fin.get("version").asLong()).isEqualTo(n + 1); // every writer eventually created exactly one version
        TreeSet<String> expected = new TreeSet<>();
        String bulletTag = fin.get("tree").get("sections").get(1).get("children").get(0).get("children").get(0).get("tags").get(0).asText();
        String eduTag = fin.get("tree").get("sections").get(0).get("children").get(0).get("tags").get(0).asText();
        expected.add("exp-1-b1:" + bulletTag);
        expected.add("edu-1:" + eduTag);
        TreeSet<String> actual = new TreeSet<>();
        index.entries(fin.get("id").asLong()).forEach(e -> actual.add(e.nodeId() + ":" + e.slug()));
        assertThat(actual).isEqualTo(expected);
        assertThat(bulletTag).isEqualTo(eduTag); // each saved tree is internally consistent: no mixed index from interleaved writers
    }
}
