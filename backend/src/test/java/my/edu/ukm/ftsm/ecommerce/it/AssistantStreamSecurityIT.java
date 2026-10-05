package my.edu.ukm.ftsm.ecommerce.it;

import my.edu.ukm.ftsm.ecommerce.security.JwtUtils;
import my.edu.ukm.ftsm.ecommerce.service.AssistantService;
import my.edu.ukm.ftsm.ecommerce.model.Role;
import my.edu.ukm.ftsm.ecommerce.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * Authentication of the streaming endpoint on a REAL Tomcat with the real SecurityConfig and JwtAuthFilter
 * (docs/phases/P4b.md §6.7). MockMvc cannot prove the async-dispatch behaviour, so it is not used. The outcome of case 1
 * decides whether JwtAuthFilter has to save the SecurityContext for the async dispatch.
 */
class AssistantStreamSecurityIT extends AbstractIntegrationTest {

    private static final long USER = 110_000_000L;

    @MockitoBean
    AssistantService assistantService;

    @Value("${app.jwt.secret}")
    String secret;

    @BeforeEach
    void stubTheModel() {
        when(assistantService.stream(anyLong(), any(), any())).thenReturn(Flux.just("Hel", "lo"));
    }

    @Test
    void case1ValidTokenStreamsTokenEvents() {
        HttpResponse<String> r = post("/api/assistant/stream", tokenFor(USER));
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type")).hasValueSatisfying(ct -> assertThat(ct).startsWith("text/event-stream"));
        assertThat(r.body()).contains("event:token").contains("data:Hel").contains("event:done");
    }

    @Test
    void case2NoTokenIsForbidden() {
        assertThat(post("/api/assistant/stream", null).statusCode()).isEqualTo(403);
    }

    @Test
    void case3ForgedSignatureIsForbidden() {
        String token = tokenFor(USER);
        String forged = token.substring(0, token.lastIndexOf('.') + 1) + "AAAA" + token.substring(token.lastIndexOf('.') + 5);
        assertThat(post("/api/assistant/stream", forged).statusCode()).isEqualTo(403);
    }

    @Test
    void case4ExpiredTokenIsForbidden() {
        String expired = new JwtUtils(secret, -60_000L).generateToken(User.builder().id(USER)
                .email("it" + USER + "@siswa.ukm.edu.my").role(Role.STUDENT).name("it user").build());
        assertThat(post("/api/assistant/stream", expired).statusCode()).isEqualTo(403);
    }

    @Test
    void case5ValidTokenOnTheAggregatingChatEndpoint() {
        assertThat(post("/api/chat", tokenFor(USER)).statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> post(String path, String token) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"message\":\"hi\",\"conversationId\":\"conv1\"}"));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
