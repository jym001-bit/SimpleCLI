package core.api;

import core.model.Order;
import core.model.OrderLine;
import core.model.Product;
import core.repo.InMemoryOrderRepository;
import core.repo.OrderRepository;
import core.service.OrderService;
import core.service.PricingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenCancelTest {

    @Test
    void controllerCancelsExistingOrder() {
        OrderRepository repository = new InMemoryOrderRepository();
        OrderService service = new OrderService(repository, new PricingService());
        service.addProduct(new Product("apple", "Apple", 100));
        OrderController controller = new OrderController(service);

        Order order = controller.placeOrder("o1", "c1", List.of(new OrderLine("apple", 1)));

        assertTrue(controller.cancel(order.id()));
        assertTrue(repository.find(order.id()).isEmpty());
        assertFalse(controller.cancel(order.id()));
    }
}