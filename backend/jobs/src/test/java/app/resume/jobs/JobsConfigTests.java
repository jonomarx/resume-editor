package app.resume.jobs;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import app.resume.jobs.fetch.UrlNotAllowedException;
import org.junit.jupiter.api.Test;

class JobsConfigTests {
    @Test
    void theApplicationBeanIsPublicOnlyUnlessTheSettingIsTrue() {
        var config = new JobsConfig();
        assertThatThrownBy(() -> config.publicHostPolicy(false).checkLiteral("http://127.0.0.1/")).isInstanceOf(UrlNotAllowedException.class);
        assertThatCode(() -> config.publicHostPolicy(true).checkLiteral("http://127.0.0.1/")).doesNotThrowAnyException();
    }
}
