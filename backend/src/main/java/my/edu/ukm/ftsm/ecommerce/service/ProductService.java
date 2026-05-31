package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.ProductDtos.*;
import my.edu.ukm.ftsm.ecommerce.exception.ResourceNotFoundException;
import my.edu.ukm.ftsm.ecommerce.model.Product;
import my.edu.ukm.ftsm.ecommerce.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    public ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public List<ProductResponse> list(String keyword) {
        List<Product> products = (keyword != null && !keyword.isBlank())
                ? productRepository.findByNameContainingIgnoreCase(keyword)
                : productRepository.findAll();
        return products.stream().map(ProductResponse::from).toList();
    }

    public ProductResponse get(Long id) {
        return ProductResponse.from(find(id));
    }

    public ProductResponse create(CreateProductRequest req) {
        Product p = Product.builder()
                .name(req.name())
                .description(req.description())
                .price(req.price())
                .imageUrl(req.imageUrl())
                .totalStock(req.totalStock())
                .category(req.category())
                .build();
        return ProductResponse.from(productRepository.save(p));
    }

    public ProductResponse update(Long id, CreateProductRequest req) {
        Product p = find(id);
        p.setName(req.name());
        p.setDescription(req.description());
        p.setPrice(req.price());
        p.setImageUrl(req.imageUrl());
        p.setTotalStock(req.totalStock());
        p.setCategory(req.category());
        return ProductResponse.from(productRepository.save(p));
    }

    public void delete(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Product not found: " + id);
        }
        productRepository.deleteById(id);
    }

    public Product find(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
    }
}
