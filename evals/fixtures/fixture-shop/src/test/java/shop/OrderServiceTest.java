package shop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderServiceTest {

    @Test
    void calculatesTotalFromPrices() {
        OrderService service = new OrderService(new UserService());
        service.setPrice("apple", 3.5);
        service.setPrice("pear", 2.0);

        assertEquals(9.0, service.calculateTotal(Map.of("apple", 2, "pear", 1)), 0.0001);
    }

    @Test
    void rejectsUnknownUser() {
        OrderService service = new OrderService(new UserService());
        service.setPrice("apple", 3.5);

        assertThrows(IllegalArgumentException.class,
                () -> service.createOrder("missing-user", Map.of("apple", 1)));
    }
}
