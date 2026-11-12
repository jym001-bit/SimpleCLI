package core.repo;

import core.model.Order;
import core.model.OrderLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryOrderRepositoryTest {

    @Test
    void savesAndFindsOrder() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        Order order = new Order("o1", "c1", List.of(new OrderLine("apple", 1)), 100);

        repository.save(order);

        assertTrue(repository.find("o1").isPresent());
        assertEquals(1, repository.all().size());
    }
}
