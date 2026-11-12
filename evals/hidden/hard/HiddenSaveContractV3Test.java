package core.repo;

import core.model.OrderLine;
import core.model.Product;
import core.service.OrderService;
import core.service.PricingService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * hard.v3 补充隐藏测试：堵住“只把 save 改成返回 boolean、createOrder 仍然忽略重复 id”的实现。
 */
class HiddenSaveContractV3Test {

    @Test
    void createOrderRejectsDuplicateId() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        OrderService service = new OrderService(repository, new PricingService());
        service.addProduct(new Product("apple", "Apple", 100));
        service.createOrder("o1", "c1", List.of(new OrderLine("apple", 1)));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.createOrder("o1", "c1", List.of(new OrderLine("apple", 1))));
        assertTrue(error.getMessage() != null && error.getMessage().contains("o1"));
    }
}
