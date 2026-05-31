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
     * Hot path. Returns 202 Accepted on success with a tracking token; the order
     * is persisted asynchronously by the stream consumer.
     */
    @PostMapping("/{eventId}/buy")
    public ResponseEntity<SeckillBuyResponse> buy(@AuthenticationPrincipal AuthPrincipal principal,
                                                  @PathVariable Long eventId) {
        SeckillBuyResponse resp = seckillService.buy(principal.userId(), eventId);
        HttpStatus status = "ACCEPTED".equals(resp.result()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(resp);
    }

    @GetMapping("/result")
    public SeckillResultResponse result(@RequestParam String token) {
        return seckillService.result(token);
    }
}
