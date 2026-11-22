package ru.spbstu.ghanalytics.core.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Learning test: pins how Jackson's tree reacts to a missing field.
 * A cheat sheet for response parsers: prefer {@code path} and check {@code isMissingNode()}.
 */
class JacksonLearningTest {

    private JsonNode root;

    @BeforeEach
    void parse() throws Exception {
        root = JsonMapper.builder().build()
                .readTree("{\"repository\": {\"name\": \"Hello-World\", \"stargazerCount\": 42}}");
    }

    @Test
    void getReturnsNodeWhenFieldExists() {
        assertThat(root.get("repository").get("name").asText()).isEqualTo("Hello-World");
    }

    @Test
    void getReturnsNullWhenFieldIsMissingSoNextGetFails() {
        assertThat(root.get("owner")).isNull();
        assertThatThrownBy(() -> root.get("owner").get("login")).isInstanceOf(NullPointerException.class);
    }

    @Test
    void pathReturnsMissingNodeAndChainContinues() {
        JsonNode login = root.path("owner").path("login");

        assertThat(login.isMissingNode()).isTrue();
    }

    @Test
    void asIntOnMissingFieldIsSilentZero() {
        assertThat(root.path("repository").path("stargazerCount").asInt()).isEqualTo(42);
        assertThat(root.path("repository").path("forkCount").asInt()).isZero();   // no error: check isMissingNode()
    }

    @Test
    void atFollowsJsonPointer() {
        assertThat(root.at("/repository/stargazerCount").asInt()).isEqualTo(42);
        assertThat(root.at("/repository/owner/login").isMissingNode()).isTrue();
    }
}
