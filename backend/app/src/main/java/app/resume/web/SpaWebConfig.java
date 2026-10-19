package app.resume.web;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the compiled Vite build from the jar's {@code static/} folder (same origin as the API).
 * Hashed files under {@code /assets/} are cached for a year; everything else, including index.html, is always
 * revalidated. A path that is not an {@code /api} call and not a file (no extension) falls back to index.html so
 * client-side routes survive a reload.
 */
@Configuration
public class SpaWebConfig implements WebMvcConfigurer {
    static final String LOCATION = "classpath:/static/";

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/").setViewName("forward:/index.html");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(LOCATION + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
        registry.addResourceHandler("/**")
                .addResourceLocations(LOCATION)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    static class SpaFallbackResolver extends PathResourceResolver {
        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable() && !resourcePath.isEmpty()) {
                return requested;
            }
            if (resourcePath.equals("api") || resourcePath.startsWith("api/") || hasExtension(resourcePath)) {
                return null;
            }
            Resource index = new ClassPathResource("static/index.html");
            return index.exists() ? index : null;
        }

        private static boolean hasExtension(String path) {
            String last = path.substring(path.lastIndexOf('/') + 1);
            return last.contains(".");
        }
    }
}
