package app.resume.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link LlmClient} the application uses: delegates to the configured {@link LlmProvider} and checks structured
 * answers against their JSON Schema. Output is untrusted: it is only parsed and validated here, never executed, and a
 * provider failure other than an {@link LlmException} becomes `llm_unavailable`.
 */
public class ValidatingLlmClient implements LlmClient {
    private static final JsonSchemaFactory FACTORY = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    private final LlmProvider provider;
    private final ObjectMapper mapper;

    public ValidatingLlmClient(LlmProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    @Override
    public String complete(LlmRequest request) {
        try {
            String text = provider.generate(request);
            if (text == null) throw new LlmUnavailableException("the provider returned no text");
            return text;
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("the LLM provider failed", e);
        }
    }

    @Override
    public String completeJson(LlmRequest request, String jsonSchema) {
        JsonSchema schema = FACTORY.getSchema(jsonSchema); // a malformed schema is a programming error: let it throw
        String text = complete(request);
        JsonNode json;
        try {
            // exactly one JSON document: trailing text after it is rejected, so the returned text is that document
            json = mapper.readerFor(JsonNode.class).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new LlmInvalidOutputException("the model's answer is not valid JSON", List.of("answer is not a single valid JSON document"));
        }
        if (json == null || json.isMissingNode()) {
            throw new LlmInvalidOutputException("the model's answer is empty", List.of("$: empty answer"));
        }
        List<String> problems = new ArrayList<>();
        // Only our own wording: the violated schema keyword and its place in OUR schema. Neither the validator's message
        // nor the instance location is used, because both can contain model-chosen text (property names).
        schema.validate(json).forEach(m -> problems.add(m.getType() + " violated at schema path " + m.getEvaluationPath()));
        if (!problems.isEmpty()) {
            throw new LlmInvalidOutputException("the model's answer does not match the schema", problems);
        }
        return text;
    }
}
