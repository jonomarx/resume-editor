package app.resume.jobs.extract;

import org.jsoup.Jsoup;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * DUMMY(US-037): stand-in for the real LLM so the pipeline can run without a provider. Returns one posting built from
 * the page's {@code <title>}; no model is called.
 */
@Component
@Profile("dummy")
public class DummyExtractionLlm implements ExtractionLlm {
    @Override
    public String complete(String systemPrompt, String userPrompt) {
        String title = Jsoup.parse(userPrompt.replace("\\n", "\n")).title();
        if (title.isBlank()) title = "Software Engineering Intern";
        return "{\"postings\":[{\"title\":" + quote(title) + ",\"company\":\"Example Co\",\"location\":\"Remote\","
                + "\"description\":\"Dummy extraction.\",\"url\":null,\"datePosted\":null,\"employmentType\":\"INTERN\"}]}";
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replaceAll("[\\p{Cntrl}]", " ") + "\"";
    }
}
