package ru.spbstu.ghanalytics.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The search string is a pure function; the count is read from a stubbed client, no network. */
class GitHubRepoCounterTest {

    private static final DateRange MARCH = new DateRange(LocalDate.of(2021, 3, 1), LocalDate.of(2021, 3, 31));

    private final ObjectMapper json = new ObjectMapper();
    private final GitHubGraphQlClient client = mock(GitHubGraphQlClient.class);

    @Test
    void searchQueryForDays() {
        assertThat(GitHubRepoCounter.searchQuery(MARCH, 10))
                .isEqualTo("is:public stars:>=10 created:2021-03-01..2021-03-31");
    }

    @Test
    void searchQueryForTimeWindow() {
        assertThat(GitHubRepoCounter.searchQuery("created:2021-03-15T10:00:00Z..2021-03-15T10:09:59Z", 50))
                .isEqualTo("is:public stars:>=50 created:2021-03-15T10:00:00Z..2021-03-15T10:09:59Z");
    }

    @Test
    void searchQueryNeedsCreatedQualifier() {
        assertThatThrownBy(() -> GitHubRepoCounter.searchQuery("pushed:2021-03-01..2021-03-31", 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryHasItsOwnOperationName() {
        String query = GitHubRepoCounter.loadQuery(GitHubRepoCounter.QUERY_RESOURCE);

        assertThat(GitHubGraphQlClient.operationName(query)).isEqualTo("CountRepos");
        assertThat(query).contains("repositoryCount").contains("rateLimit");
    }

    @Test
    void countReadsRepositoryCount() throws Exception {
        when(client.execute(anyString(), eq(Map.of("q", "is:public stars:>=10 created:2021-03-01..2021-03-31"))))
                .thenReturn(json.readTree("{\"search\": {\"repositoryCount\": 22197}, \"rateLimit\": {\"cost\": 1}}"));

        assertThat(new GitHubRepoCounter(client, 10).count(MARCH)).isEqualTo(22197);
        verify(client).execute(anyString(), eq(Map.of("q", "is:public stars:>=10 created:2021-03-01..2021-03-31")));
    }

    @Test
    void missingCountIsAnErrorNotZero() throws Exception {
        when(client.execute(anyString(), eq(Map.of("q", "is:public stars:>=10 created:2021-03-01..2021-03-31"))))
                .thenReturn(json.readTree("{\"search\": {}}"));

        assertThatThrownBy(() -> new GitHubRepoCounter(client, 10).count(MARCH))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("repositoryCount");
    }

    @Test
    void missingResourceIsReported() {
        assertThatThrownBy(() -> GitHubRepoCounter.loadQuery("no-such.graphql"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no-such.graphql");
    }
}
