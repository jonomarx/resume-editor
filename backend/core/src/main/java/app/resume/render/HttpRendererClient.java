package app.resume.render;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Talks to the renderer over HTTP with Spring's {@link RestClient}: POST /render with the LaTeX source as the UTF-8 text
 * body. Built from the auto-configured builder (so Boot's customizers, observability and message converters apply), with
 * the timeouts from {@link RendererProperties} applied through Boot's request factory settings.
 */
@Component
@EnableConfigurationProperties(RendererProperties.class)
public class HttpRendererClient implements RendererClient {
    private static final MediaType TEXT_UTF8 = new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8);

    private final RestClient client;
    private final ObjectMapper mapper;

    @Autowired
    public HttpRendererClient(RestClient.Builder builder, RendererProperties props, ObjectMapper mapper) {
        this(builder.clone()
                .baseUrl(props.url().replaceAll("/+$", ""))
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(ClientHttpRequestFactorySettings.defaults()
                        .withConnectTimeout(Duration.ofSeconds(props.connectTimeoutSeconds()))
                        .withReadTimeout(Duration.ofSeconds(props.timeoutSeconds()))))
                .build(), mapper);
    }

    /** For tests: a ready-made client (for example bound to a MockRestServiceServer). */
    HttpRendererClient(RestClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    @Override
    public Outcome render(String tex) {
        try {
            return client.post().uri("/render").contentType(TEXT_UTF8).body(tex).exchange((request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                byte[] body = response.getBody().readAllBytes();
                return switch (status.value()) {
                    case 200 -> pdfOutcome(body, response.getHeaders().getFirst("X-Page-Count"));
                    case 422 -> new CompileError(logOf(body));
                    case 413 -> new TooLarge();
                    case 504 -> new Unavailable("renderer timed out");
                    default -> new Unavailable("renderer answered " + status.value());
                };
            });
        } catch (RestClientException | UncheckedIOException e) { // connection refused, connect or read timeout, broken stream
            return new Unavailable("renderer unreachable");
        }
    }

    /** A 200 without a usable X-Page-Count is a broken renderer, not a one-page document. */
    private static Outcome pdfOutcome(byte[] body, String header) {
        try {
            int pages = Integer.parseInt(header == null ? "" : header.trim());
            if (pages < 1) return new Unavailable("renderer sent an invalid page count");
            return new Pdf(body, pages, "");
        } catch (NumberFormatException e) {
            return new Unavailable("renderer sent a missing or non-numeric page count");
        }
    }

    private String logOf(byte[] body) throws IOException {
        JsonNode json = mapper.readTree(body);
        return json.hasNonNull("log") ? json.get("log").asText() : "";
    }
}
