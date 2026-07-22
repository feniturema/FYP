package my.edu.ukm.ftsm.ecommerce.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import my.edu.ukm.ftsm.ecommerce.dto.ItemDtos.ItemDraftResponse;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;

/**
 * Innovation 2 — Multimodal AI listing draft.
 * Sends the uploaded image to GPT-4o Vision and returns a pre-filled draft
 * (title, description, category, condition, suggestedPrice) that the student
 * can review and edit before publishing their C2C listing.
 */
@Service
public class ListingDraftService {

    private static final Logger log = LoggerFactory.getLogger(ListingDraftService.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final String openAiApiKey;
    private final String visionModel;
    private final String uploadDir;
    private final WebClient openAiClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ListingDraftService(
            @Value("${app.openai.api-key:}") String openAiApiKey,
            @Value("${app.openai.vision-model:gpt-4o}") String visionModel,
            @Value("${app.upload.dir:uploads}") String uploadDir) {
        this.openAiApiKey = openAiApiKey;
        this.visionModel = visionModel;
        this.uploadDir = uploadDir;
        this.openAiClient = WebClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .build();
    }

    public boolean isConfigured() {
        return openAiApiKey != null && !openAiApiKey.isBlank();
    }

    /**
     * @param imageUrl server-relative URL like "/uploads/uuid.png"
     * @return draft fields extracted from the image
     */
    public ItemDraftResponse generateDraft(String imageUrl) {
        if (!isConfigured()) {
            throw new BusinessException("AI listing draft is not configured (missing OPENAI_API_KEY).");
        }

        String dataUri = toBase64DataUri(imageUrl);

        String prompt = """
                You are helping a university student sell a second-hand item on a campus marketplace.
                Look at this photo and return ONLY a JSON object with these fields:
                {
                  "title": "concise product title (max 60 chars)",
                  "description": "2-3 sentences describing the item, condition, and why it is being sold",
                  "category": "one of: Electronics, Books, Clothing, Furniture, Sports, Accessories, Stationery, Other",
                  "condition": "one of: NEW, LIKE_NEW, USED",
                  "suggestedPrice": fair market price in Malaysian Ringgit as a number (no currency symbol)
                }
                Base your price on the item's apparent brand, condition, and typical second-hand value in Malaysia.
                Return ONLY the JSON object, no markdown, no explanation.
                """;

        // Build request body with ObjectNode to guarantee correct JSON structure
        com.fasterxml.jackson.databind.node.ObjectNode body = objectMapper.createObjectNode();
        body.put("model", visionModel);
        body.put("max_tokens", 512);
        body.put("temperature", 0.2);
        com.fasterxml.jackson.databind.node.ArrayNode messages = body.putArray("messages");
        com.fasterxml.jackson.databind.node.ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        com.fasterxml.jackson.databind.node.ArrayNode content2 = userMsg.putArray("content");
        content2.addObject().put("type", "text").put("text", prompt);
        com.fasterxml.jackson.databind.node.ObjectNode imgPart = content2.addObject();
        imgPart.put("type", "image_url");
        imgPart.putObject("image_url").put("url", dataUri).put("detail", "low");

        try {
            byte[] bodyBytes = objectMapper.writeValueAsBytes(body);
            log.info("[ListingDraft] dataUri prefix: {}", dataUri.substring(0, Math.min(40, dataUri.length())));
            log.info("[ListingDraft] body bytes: {}", bodyBytes.length);
            JsonNode resp = openAiClient.post()
                    .uri("/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + openAiApiKey)
                    .header("Content-Type", "application/json")
                    .body(reactor.core.publisher.Mono.just(bodyBytes),
                            byte[].class)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(TIMEOUT);

            String content = resp.at("/choices/0/message/content").asText().strip();
            log.info("[ListingDraft] raw content: {}", content);
            // Strip markdown code fences if model wraps in ```json ... ```
            if (content.startsWith("```")) {
                content = content.replaceAll("^```[a-z]*\\n?", "").replaceAll("```$", "").strip();
            }
            // Extract embedded JSON object if model returned prose + JSON
            if (!content.startsWith("{")) {
                int start = content.indexOf('{');
                int end = content.lastIndexOf('}');
                if (start != -1 && end != -1 && end > start) {
                    content = content.substring(start, end + 1);
                } else {
                    throw new BusinessException("Model returned non-JSON: " + content.substring(0, Math.min(200, content.length())));
                }
            }

            JsonNode draft = objectMapper.readTree(content);
            return new ItemDraftResponse(
                    draft.path("title").asText(""),
                    draft.path("description").asText(""),
                    draft.path("category").asText("Other"),
                    draft.path("condition").asText("USED"),
                    BigDecimal.valueOf(draft.path("suggestedPrice").asDouble(10.0))
            );
        } catch (Exception e) {
            log.error("[ListingDraft] Vision API call failed: {}", e.getMessage());
            throw new BusinessException("AI draft failed: " + e.getMessage());
        }
    }

    private String toBase64DataUri(String imageUrl) {
        // External URLs (http/https): pass directly — GPT-4o accepts public image_url
        if (imageUrl.startsWith("http://") || imageUrl.startsWith("https://")) {
            return imageUrl;
        }
        // Local uploads: imageUrl is like "/uploads/uuid.png" — read from disk
        String relative = imageUrl.startsWith("/") ? imageUrl.substring(1) : imageUrl;
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(uploadDir).resolve(
                    relative.replaceFirst("^uploads/", "")));
            String mimeType = relative.endsWith(".png") ? "image/png"
                    : relative.endsWith(".webp") ? "image/webp"
                    : "image/jpeg";
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            throw new BusinessException("Could not read uploaded image: " + e.getMessage());
        }
    }
}
