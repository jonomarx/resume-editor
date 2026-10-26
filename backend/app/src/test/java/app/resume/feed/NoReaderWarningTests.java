package app.resume.feed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** US-117: without a PostingReader the feed is empty and says so in the log, at startup and (rate-limited) per request. */
@ExtendWith(OutputCaptureExtension.class)
class NoReaderWarningTests {
    JobFeedService service() {
        return new JobFeedService(new StaticListableBeanFactory().getBeanProvider(app.resume.jobs.PostingReader.class), null, null, null);
    }

    static final JobFeedService.Filters F = new JobFeedService.Filters(false, java.util.Set.of(), null, null, null, null, 50, 0);

    @Test
    void warnsAtStartup(CapturedOutput out) {
        service().warnAtStartup();
        assertThat(out.getAll()).contains("No PostingReader bean is configured");
    }

    @Test
    void warnsOnRequestsButOnlyOncePerInterval(CapturedOutput out) {
        JobFeedService s = service();
        assertThat(s.list(1, F).items()).isEqualTo(List.of());
        s.list(1, F);
        s.detail(1, 5);
        assertThat(out.getAll().split("No PostingReader bean is configured", -1).length - 1).isEqualTo(1);
    }
}
