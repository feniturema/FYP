package my.edu.ukm.ftsm.ecommerce.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/** Async, non-blocking client for outbound Gemini calls. */
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient geminiWebClient(@Value("${app.gemini.base-url}") String baseUrl) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .build();
    }
}
