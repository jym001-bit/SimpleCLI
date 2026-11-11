package shop;

import java.util.HashMap;
import java.util.Map;

public class OrderService {

    private final UserService userService;
    private final Map<String, Double> prices = new HashMap<>();
    private int sequence;

    public OrderService(UserService userService) {
        this.userService = userService;
    }

    public void setPrice(String sku, double price) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        if (price < 0) {
            throw new IllegalArgumentException("price must be >= 0");
        }
        prices.put(sku, price);
    }

    public Order createOrder(String userId, Map<String, Integer> items) {
        userService.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("unknown user: " + userId));
        double total = calculateTotal(items);
        return new Order("order-" + (++sequence), userId, Map.copyOf(items), total);
    }

    public double calculateTotal(Map<String, Integer> items) {
        double total = 0;
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            Double unitPrice = prices.get(entry.getKey());
            if (unitPrice == null) {
                throw new IllegalArgumentException("unknown sku: " + entry.getKey());
            }
            total += unitPrice * entry.getValue();
        }
        return total;
    }
}
