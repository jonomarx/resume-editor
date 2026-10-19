package app.resume.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Talks to the renderer over HTTP: POST /render with the LaTeX source as the body. URL from RENDERER_URL. */
@Component
public class HttpRendererClient implements RendererClient {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final URI endpoint;
    private final Duration timeout;
    private final ObjectMapper mapper;

    public HttpRendererClient(@Value("${app.renderer.url}") String url,
            @Value("${app.renderer.timeout-seconds:30}") long timeoutSeconds, ObjectMapper mapper) {
        this.endpoint = URI.create(url.replaceAll("/+$", "") + "/render");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.mapper = mapper;
    }

    @Override
    public Outcome render(String tex) {
        HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("Content-Type", "text/plain; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(tex, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<byte[]> res = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            return switch (res.statusCode()) {
                case 200 -> new Pdf(res.body(), res.headers().firstValue("X-Page-Count").map(Integer::valueOf).orElse(null), "");
                case 422 -> new CompileError(logOf(res.body()));
                case 413 -> new TooLarge();
                case 504 -> new Unavailable("renderer timed out");
                default -> new Unavailable("renderer answered " + res.statusCode());
            };
        } catch (IOException e) {
            return new Unavailable("renderer unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Unavailable("interrupted");
        }
    }

    private String logOf(byte[] body) throws IOException {
        JsonNode json = mapper.readTree(body);
        return json.hasNonNull("log") ? json.get("log").asText() : "";
    }
}
