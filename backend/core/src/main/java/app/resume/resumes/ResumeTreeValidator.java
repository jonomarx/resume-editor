package app.resume.resumes;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Validates a resume tree against schema/resume-tree.schema.json, plus the rule a schema cannot express: unique ids. */
@Component
public class ResumeTreeValidator {
    private final JsonSchema schema;
    private final ObjectMapper mapper;

    public ResumeTreeValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("schema/resume-tree.schema.json")) {
            this.schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the problems found (empty means the tree is valid): schema violations, duplicate ids, and the
     * `{{children}}` token rules, each token problem naming its node id (the contract's 400 `details`).
     */
    public List<TreeProblem> validate(JsonNode tree) {
        List<TreeProblem> problems = new ArrayList<>();
        schema.validate(tree).forEach(m -> problems.add(new TreeProblem(m.getInstanceLocation().toString(), m.getMessage())));
        if (problems.isEmpty()) {
            Set<String> seen = new HashSet<>();
            collectIds(tree.get("id"), seen, problems);
            collectIds(tree.get("header").get("id"), seen, problems);
            collectIds(tree.get("footer").get("id"), seen, problems);
            for (JsonNode s : tree.get("sections")) walk(s, seen, problems);
        }
        if (problems.isEmpty()) problems.addAll(LatexAssembler.checkTokens(parse(tree)));
        return problems;
    }

    public ResumeTree parse(JsonNode tree) {
        return mapper.convertValue(tree, ResumeTree.class);
    }

    private void walk(JsonNode node, Set<String> seen, List<TreeProblem> problems) {
        collectIds(node.get("id"), seen, problems);
        for (JsonNode c : node.get("children")) walk(c, seen, problems);
    }

    private static void collectIds(JsonNode id, Set<String> seen, List<TreeProblem> problems) {
        if (!seen.add(id.asText())) problems.add(new TreeProblem(id.asText(), "duplicate node id: " + id.asText()));
    }
}
