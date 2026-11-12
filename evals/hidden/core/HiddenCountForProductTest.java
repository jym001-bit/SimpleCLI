package core.api;

import core.model.OrderLine;
import core.model.Product;
import core.repo.InMemoryOrderRepository;
import core.service.OrderService;
import core.service.PricingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HiddenCountForProductTest {

    @Test
    void countsQuantitiesAcrossOrders() {
        OrderService service = new OrderService(new InMemoryOrderRepository(), new PricingService());
        service.addProduct(new Product("apple", "Apple", 100));
        OrderController controller = new OrderController(service);
        controller.placeOrder("o1", "c1", List.of(new OrderLine("apple", 2)));
        controller.placeOrder("o2", "c1", List.of(new OrderLine("apple", 3)));

        assertEquals(5, controller.countForProduct("apple"));
    }
}