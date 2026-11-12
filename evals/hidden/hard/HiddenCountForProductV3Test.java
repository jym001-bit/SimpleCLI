package core.api;

import core.model.OrderLine;
import core.model.Product;
import core.repo.InMemoryOrderRepository;
import core.service.OrderService;
import core.service.PricingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * hard.v3 补充隐藏测试：堵住“不按 productId 过滤、直接把所有订单行数量求和”的实现。
 */
class HiddenCountForProductV3Test {

    @Test
    void countsOnlyMatchingProduct() {
        OrderService service = new OrderService(new InMemoryOrderRepository(), new PricingService());
        service.addProduct(new Product("apple", "Apple", 100));
        service.addProduct(new Product("pear", "Pear", 50));
        OrderController controller = new OrderController(service);
        controller.placeOrder("o1", "c1", List.of(new OrderLine("apple", 2), new OrderLine("pear", 3)));
        controller.placeOrder("o2", "c1", List.of(new OrderLine("apple", 4)));

        assertEquals(6, controller.countForProduct("apple"));
        assertEquals(3, controller.countForProduct("pear"));
        assertEquals(0, controller.countForProduct("banana"));
    }
}
