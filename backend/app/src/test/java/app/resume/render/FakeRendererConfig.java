package app.resume.render;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/** Fast-loop renderer stand-in (the real container is used only in the slow loop). */
@Configuration
@Profile("test")
class FakeRendererConfig {
    static final AtomicInteger CALLS = new AtomicInteger();

    @Bean
    @Primary
    RendererClient fakeRenderer() {
        return tex -> {
            CALLS.incrementAndGet();
            if (tex.contains("\\unavailable")) return new RendererClient.Unavailable("down");
            int boom = tex.indexOf("\\boom");
            if (boom >= 0) {
                int line = tex.substring(0, boom).split("\n", -1).length; // 1-based line of \boom
                String lineText = tex.lines().toList().get(line - 1);
                return new RendererClient.CompileError("This is pdfTeX\n(./doc.tex\n! Undefined control sequence.\n<recently read> \\boom\n\n"
                        + "l." + line + " " + lineText + "\n\nNo pages of output.\n");
            }
            int pages = 1 + tex.split("\\\\newpage", -1).length - 1;
            return new RendererClient.Pdf(("%PDF-fake " + tex.hashCode()).getBytes(StandardCharsets.UTF_8), pages, "fake log");
        };
    }
}
