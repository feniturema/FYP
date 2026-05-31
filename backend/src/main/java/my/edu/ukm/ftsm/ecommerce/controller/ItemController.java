package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.ItemDtos.*;
import my.edu.ukm.ftsm.ecommerce.security.AuthPrincipal;
import my.edu.ukm.ftsm.ecommerce.service.ItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/items")
public class ItemController {

    private final ItemService itemService;

    public ItemController(ItemService itemService) {
        this.itemService = itemService;
    }

    @GetMapping
    public List<ItemResponse> list(@RequestParam(required = false) String category,
                                   @RequestParam(required = false) String q) {
        return itemService.list(category, q);
    }

    @GetMapping("/{id}")
    public ItemResponse get(@PathVariable Long id) {
        return itemService.get(id);
    }

    @PostMapping
    public ResponseEntity<ItemResponse> create(@AuthenticationPrincipal AuthPrincipal principal,
                                               @Valid @RequestBody CreateItemRequest req) {
        return ResponseEntity.ok(itemService.create(principal.userId(), req));
    }

    @PutMapping("/{id}")
    public ItemResponse update(@AuthenticationPrincipal AuthPrincipal principal,
                               @PathVariable Long id, @Valid @RequestBody CreateItemRequest req) {
        return itemService.update(principal.userId(), id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthPrincipal principal,
                                       @PathVariable Long id) {
        itemService.delete(principal.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
