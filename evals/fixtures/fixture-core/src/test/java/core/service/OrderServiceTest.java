package core.service;

import core.repo.InMemoryOrderRepository;
import core.model.Order;
import core.model.OrderLine;
import core.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderServiceTest {

    @Test
    void createsOrderWithSubtotalWhenBelowBulkThreshold() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        OrderService service = new OrderService(repository, new PricingService());
        service.addProduct(new Product("apple", "Apple", 100));

        Order order = service.createOrder("o1", "c1", List.of(new OrderLine("apple", 2)));

        assertEquals(200, order.totalCents());
        assertTrue(repository.find("o1").isPresent());
    }
}
