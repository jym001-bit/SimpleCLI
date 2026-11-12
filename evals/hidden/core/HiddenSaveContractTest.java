package core.repo;

import core.model.Order;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenSaveContractTest {

    @Test
    void reportsWhetherOrderWasNew() {
        InMemoryOrderRepository repository = new InMemoryOrderRepository();
        Order order = new Order("o1", "c1", List.of(), 100);

        assertTrue(repository.save(order));
        assertFalse(repository.save(order));
    }
}