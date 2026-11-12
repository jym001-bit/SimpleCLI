package core.service;

import core.model.OrderLine;
import core.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PricingServiceTest {

    private final Map<String, Product> products = Map.of("apple", new Product("apple", "Apple", 100));

    @Test
    void computesSubtotal() {
        PricingService pricing = new PricingService();
        assertEquals(300, pricing.subtotal(List.of(new OrderLine("apple", 3)), products));
    }

    @Test
    void appliesTenPercentDiscountForBulkOrders() {
        PricingService pricing = new PricingService();
        assertEquals(1080, pricing.applyBulkDiscount(1200, 12));
    }
}
