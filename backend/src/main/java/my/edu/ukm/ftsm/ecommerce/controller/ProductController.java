package my.edu.ukm.ftsm.ecommerce.controller;

import my.edu.ukm.ftsm.ecommerce.dto.ProductDtos.ProductResponse;
import my.edu.ukm.ftsm.ecommerce.service.ProductService;
import my.edu.ukm.ftsm.ecommerce.service.SmartSearchService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;
    private final SmartSearchService smartSearchService;

    public ProductController(ProductService productService, SmartSearchService smartSearchService) {
        this.productService = productService;
        this.smartSearchService = smartSearchService;
    }

    @GetMapping
    public List<ProductResponse> list(@RequestParam(required = false) String q) {
        return productService.list(q);
    }

    /** Intent-aware semantic search (LLM rerank). Literal path takes precedence over /{id}. */
    @GetMapping("/smart-search")
    public List<ProductResponse> smartSearch(@RequestParam String q) {
        return smartSearchService.smartSearchProducts(q);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable Long id) {
        return productService.get(id);
    }
}
