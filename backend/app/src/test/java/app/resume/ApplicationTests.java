package app.resume;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ApplicationTests {
    @Autowired
    ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}
