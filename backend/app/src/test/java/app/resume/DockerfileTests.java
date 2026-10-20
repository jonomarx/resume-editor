package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards bug #168: the image runs as a non-root user, so DATA_DIR must exist in the image, owned by that user, be the
 * default, and be declared a volume. (The full container check is the E2E workflow, which runs the built image.)
 */
class DockerfileTests {
    final String dockerfile = read();

    static String read() {
        try {
            return Files.readString(Path.of("../../Dockerfile"));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    String finalStage() {
        return dockerfile.substring(dockerfile.lastIndexOf("\nFROM "));
    }

    @Test
    void theRuntimeImageHasAWritableDataDirectoryForTheNonRootUser() {
        String stage = finalStage();
        assertThat(stage).contains("USER 10001");
        assertThat(stage).contains("useradd --system --uid 10001");
        assertThat(stage).contains("mkdir -p /data && chown 10001:10001 /data");
        assertThat(stage).contains("ENV DATA_DIR=/data");
        assertThat(stage).contains("VOLUME /data");
        // chown and VOLUME come before USER so the directory is prepared as root and the volume inherits the ownership
        assertThat(stage.indexOf("chown 10001:10001 /data")).isLessThan(stage.indexOf("VOLUME /data"));
        assertThat(stage.indexOf("VOLUME /data")).isLessThan(stage.indexOf("USER 10001"));
    }
}
