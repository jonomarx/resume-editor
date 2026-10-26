package app.resume.jobs;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    void enricherEnvironmentVariableSelectsTheImplementation() {
        var tags = org.mockito.Mockito.mock(app.resume.vocabulary.TagVocabulary.class);
        var llm = org.mockito.Mockito.mock(app.resume.llm.LlmClient.class);
        assertThat(JobsConfig.createEnricher("deterministic", null, null, null, tags)).isInstanceOf(app.resume.jobs.enrich.DeterministicEnricher.class);
        assertThat(JobsConfig.createEnricher(null, null, null, null, tags)).isInstanceOf(app.resume.jobs.enrich.DeterministicEnricher.class);
        assertThat(JobsConfig.createEnricher("llm", null, null, llm, tags)).isInstanceOf(app.resume.jobs.enrich.PostingEnricher.class);
        assertThat(JobsConfig.createEnricher("LLM", null, null, null, tags)).isNull(); // llm chosen but no client: postings stay untagged
        assertThatThrownBy(() -> JobsConfig.createEnricher("magic", null, null, llm, tags)).isInstanceOf(IllegalStateException.class);
    }
}
