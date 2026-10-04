package my.edu.ukm.ftsm.ecommerce.controller;

import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.*;
import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.service.SeckillService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    private final SeckillService seckillService;

    public SeckillController(SeckillService seckillService) {
        this.seckillService = seckillService;
    }

    @GetMapping("/events")
    public List<SeckillEventResponse> events() {
        return seckillService.listEvents();
    }

    @GetMapping("/events/{id}")
    public SeckillEventResponse event(@PathVariable Long id) {
        return seckillService.getEvent(id);
    }

    /**
     * Hot path. 202 + tracking token once the purchase intent is durable in the outbox (the order is
     * persisted asynchronously via Kafka); 409 for SOLD_OUT / ALREADY_BOUGHT / NOT_ACTIVE; 503 when the
     * intent could not be confirmed (UNAVAILABLE). The body is a SeckillBuyResponse in every case.
     */
    @PostMapping("/{eventId}/buy")
    public ResponseEntity<SeckillBuyResponse> buy(@AuthenticationPrincipal AuthPrincipal principal,
                                                  @PathVariable Long eventId) {
        SeckillBuyResponse resp = seckillService.buy(principal.userId(), eventId);
        return ResponseEntity.status(statusFor(resp.result())).body(resp);
    }

    static HttpStatus statusFor(String result) {
        return switch (result) {
            case "ACCEPTED" -> HttpStatus.ACCEPTED;
            case "UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "SOLD_OUT", "ALREADY_BOUGHT", "NOT_ACTIVE" -> HttpStatus.CONFLICT;
            default -> throw new IllegalStateException("unknown SecKill result " + result);
        };
    }

    @GetMapping("/result")
    public SeckillResultResponse result(@RequestParam String token) {
        return seckillService.result(token);
    }
}
