package my.edu.ukm.ftsm.ecommerce.repository;

import my.edu.ukm.ftsm.ecommerce.model.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByNameContainingIgnoreCase(String keyword);

    /** Conditional decrement inside the caller's transaction; returns 0 when out of stock. */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.totalStock = p.totalStock - 1 where p.id = :id and p.totalStock > 0")
    int decrementStock(Long id);
}
