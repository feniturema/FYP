package my.edu.ukm.ftsm.ecommerce.repository;

import my.edu.ukm.ftsm.ecommerce.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByStatus(Item.Status status);
    List<Item> findByStatusAndCategory(Item.Status status, String category);
    List<Item> findByStatusAndTitleContainingIgnoreCase(Item.Status status, String keyword);
    List<Item> findBySellerId(Long sellerId);
}
