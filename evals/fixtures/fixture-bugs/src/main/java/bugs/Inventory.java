package bugs;

import java.util.HashMap;
import java.util.Map;

/**
 * 简单的库存账本。
 *
 * 约定：不存在的 SKU 库存为 0。
 */
public class Inventory {

    private final Map<String, Integer> stock = new HashMap<>();

    public void add(String sku, int quantity) {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku is required");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        stock.merge(sku, quantity, Integer::sum);
    }

    public int stockOf(String sku) {
        return stock.get(sku);
    }

    public boolean isAvailable(String sku, int quantity) {
        return stockOf(sku) >= quantity;
    }
}
