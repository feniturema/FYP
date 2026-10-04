package my.edu.ukm.ftsm.ecommerce.repository;

import my.edu.ukm.ftsm.ecommerce.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ItemRepository extends JpaRepository<Item, Long> {
    List<Item> findByStatus(Item.Status status);
    List<Item> findByStatusAndCategory(Item.Status status, String category);
    List<Item> findByStatusAndTitleContainingIgnoreCase(Item.Status status, String keyword);
    List<Item> findBySellerId(Long sellerId);

    /** Conditional ACTIVE -> SOLD inside the caller's transaction; returns 0 when no longer active. */
    @Modifying(flushAutomatically = true)
    @Query("update Item i set i.status = :sold where i.id = :id and i.status = :active")
    int markSold(Long id, Item.Status sold, Item.Status active);
}
