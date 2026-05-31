package my.edu.ukm.ftsm.ecommerce.service;

import my.edu.ukm.ftsm.ecommerce.dto.ItemDtos.*;
import my.edu.ukm.ftsm.ecommerce.exception.BusinessException;
import my.edu.ukm.ftsm.ecommerce.exception.ResourceNotFoundException;
import my.edu.ukm.ftsm.ecommerce.model.Item;
import my.edu.ukm.ftsm.ecommerce.repository.ItemRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ItemService {

    private final ItemRepository itemRepository;

    public ItemService(ItemRepository itemRepository) {
        this.itemRepository = itemRepository;
    }

    public List<ItemResponse> list(String category, String keyword) {
        List<Item> items;
        if (keyword != null && !keyword.isBlank()) {
            items = itemRepository.findByStatusAndTitleContainingIgnoreCase(Item.Status.ACTIVE, keyword);
        } else if (category != null && !category.isBlank()) {
            items = itemRepository.findByStatusAndCategory(Item.Status.ACTIVE, category);
        } else {
            items = itemRepository.findByStatus(Item.Status.ACTIVE);
        }
        return items.stream().map(ItemResponse::from).toList();
    }

    public ItemResponse get(Long id) {
        return ItemResponse.from(find(id));
    }

    public ItemResponse create(Long sellerId, CreateItemRequest req) {
        Item item = Item.builder()
                .sellerId(sellerId)
                .title(req.title())
                .description(req.description())
                .price(req.price())
                .category(req.category())
                .condition(req.condition())
                .imageUrl(req.imageUrl())
                .status(Item.Status.ACTIVE)
                .build();
        return ItemResponse.from(itemRepository.save(item));
    }

    public ItemResponse update(Long sellerId, Long id, CreateItemRequest req) {
        Item item = find(id);
        assertOwner(item, sellerId);
        item.setTitle(req.title());
        item.setDescription(req.description());
        item.setPrice(req.price());
        item.setCategory(req.category());
        item.setCondition(req.condition());
        item.setImageUrl(req.imageUrl());
        return ItemResponse.from(itemRepository.save(item));
    }

    public void delete(Long sellerId, Long id) {
        Item item = find(id);
        assertOwner(item, sellerId);
        item.setStatus(Item.Status.REMOVED);
        itemRepository.save(item);
    }

    private Item find(Long id) {
        return itemRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Item not found: " + id));
    }

    private void assertOwner(Item item, Long sellerId) {
        if (!item.getSellerId().equals(sellerId)) {
            throw new BusinessException("You can only modify your own listings.");
        }
    }
}
