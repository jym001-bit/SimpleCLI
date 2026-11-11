package shop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HiddenDiscountTest {

    @Test
    void appliesDiscountRate() {
        OrderService service = new OrderService(new UserService());
        service.setPrice("apple", 100.0);

        assertEquals(90.0, service.calculateTotal(Map.of("apple", 1), 0.1), 0.0001);
        assertEquals(100.0, service.calculateTotal(Map.of("apple", 1), 0.0), 0.0001);
    }

    @Test
    void rejectsDiscountOutsideRange() {
        OrderService service = new OrderService(new UserService());
        service.setPrice("apple", 100.0);

        assertThrows(IllegalArgumentException.class,
                () -> service.calculateTotal(Map.of("apple", 1), 1.5));
    }

    @Test
    void keepsOriginalMethodBehaviour() {
        OrderService service = new OrderService(new UserService());
        service.setPrice("apple", 100.0);

        assertEquals(100.0, service.calculateTotal(Map.of("apple", 1)), 0.0001);
    }
}
