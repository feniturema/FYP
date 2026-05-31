package my.edu.ukm.ftsm.ecommerce.controller;

import jakarta.validation.Valid;
import my.edu.ukm.ftsm.ecommerce.dto.ProductDtos.*;
import my.edu.ukm.ftsm.ecommerce.dto.SeckillDtos.*;
import my.edu.ukm.ftsm.ecommerce.service.ProductService;
import my.edu.ukm.ftsm.ecommerce.service.SeckillService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin-only management of the official B2C catalogue and SecKill events.
 * Access is restricted to ROLE_ADMIN by SecurityConfig (/api/admin/**).
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ProductService productService;
    private final SeckillService seckillService;

    public AdminController(ProductService productService, SeckillService seckillService) {
        this.productService = productService;
        this.seckillService = seckillService;
    }

    // ---- Products ----

    @PostMapping("/products")
    public ProductResponse createProduct(@Valid @RequestBody CreateProductRequest req) {
        return productService.create(req);
    }

    @PutMapping("/products/{id}")
    public ProductResponse updateProduct(@PathVariable Long id, @Valid @RequestBody CreateProductRequest req) {
        return productService.update(id, req);
    }

    @DeleteMapping("/products/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productService.delete(id);
        return ResponseEntity.noContent().build();
    }

    // ---- SecKill events ----

    @PostMapping("/seckill-events")
    public SeckillEventResponse createSeckill(@Valid @RequestBody CreateSeckillRequest req) {
        return seckillService.createEvent(req);
    }

    @GetMapping("/seckill-events")
    public List<SeckillEventResponse> listSeckillEvents() {
        return seckillService.listEvents();
    }

    @PutMapping("/seckill-events/{id}")
    public SeckillEventResponse updateSeckill(@PathVariable Long id,
                                              @Valid @RequestBody UpdateSeckillRequest req) {
        return seckillService.updateEvent(id, req);
    }

    @DeleteMapping("/seckill-events/{id}")
    public ResponseEntity<Void> deleteSeckill(@PathVariable Long id) {
        seckillService.deleteEvent(id);
        return ResponseEntity.noContent().build();
    }
}
