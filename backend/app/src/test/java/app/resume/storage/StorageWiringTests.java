package app.resume.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** FILE_STORAGE=local (the default value in application.yml) selects the local-disk implementation. */
@SpringBootTest
@ActiveProfiles("test")
class StorageWiringTests {
    @Autowired FileStorage storage;
    @Autowired ResumeLocks locks;

    @Test
    void localImplementationIsSelectedByDefault() {
        assertThat(storage).isInstanceOf(LocalFileStorage.class);
        assertThat(locks).isInstanceOf(InMemoryResumeLocks.class);
    }
}
