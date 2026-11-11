package shop;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class CartService {

    private final OrderService orderService;
    private final Map<String, Integer> items = new LinkedHashMap<>();

    public CartService(OrderService orderService) {
        this.orderService = orderService;
    }

    public void addItem(String sku, int quantity) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        items.merge(sku, quantity, Integer::sum);
    }

    public Map<String, Integer> items() {
        return Collections.unmodifiableMap(items);
    }

    public double total() {
        return orderService.calculateTotal(items);
    }

    public Order checkout(String userId) {
        Order order = orderService.createOrder(userId, Map.copyOf(items));
        items.clear();
        return order;
    }
}
