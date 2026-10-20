package app.resume.render;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Renderer client settings (application.yml, `app.renderer`; environment: RENDERER_URL). The defaults live only in
 * application.yml.
 *
 * @param url base URL of the renderer container
 * @param timeoutSeconds read timeout: how long one compile may take before the call fails
 * @param connectTimeoutSeconds how long to wait for the connection
 */
@ConfigurationProperties("app.renderer")
public record RendererProperties(String url, long timeoutSeconds,
        long connectTimeoutSeconds) {}
