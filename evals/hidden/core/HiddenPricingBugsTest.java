package core.service;

import core.model.Order;
import core.model.OrderLine;
import core.model.Product;
import core.repo.InMemoryOrderRepository;
import core.util.Money;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HiddenPricingBugsTest {

    @Test
    void moneyRoundsHalfUp() {
        assertEquals(100, Money.round(99.5));
        assertEquals(100, Money.round(99.6));
    }

    @Test
    void bulkDiscountStartsAboveTenItems() {
        PricingService pricing = new PricingService();
        assertEquals(1000, pricing.applyBulkDiscount(1000, 10));
        assertEquals(900, pricing.applyBulkDiscount(1000, 11));
    }

    @Test
    void orderTotalIncludesDiscount() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        OrderService service = new OrderService(repository, new PricingService());
        service.addProduct(new Product("apple", "Apple", 100));

        Order order = service.createOrder("o1", "c1", List.of(new OrderLine("apple", 11)));

        assertEquals(990, order.totalCents());
    }
}