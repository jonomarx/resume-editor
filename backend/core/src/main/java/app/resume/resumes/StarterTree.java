package app.resume.resumes;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Component;

/**
 * The tree new users start with (US-014 seeds the master resume from it): Jake's sample content broken into nodes
 * under Education, Experience, Projects and Skills. TEMPLATE: the content comes from the template resource folder.
 */
@Component
public class StarterTree {
    private final ObjectMapper mapper;
    private final TemplateResources template;

    public StarterTree(ObjectMapper mapper) {
        this.mapper = mapper;
        this.template = TemplateResources.standard();
    }

    /** A fresh copy each call. */
    public ResumeTree create() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(template.starterTreeResource())) {
            return mapper.readValue(in, ResumeTree.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
