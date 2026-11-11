package shop;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenOrderHistoryTest {

    @Test
    void recordsOrdersPerUserInCreationOrder() {
        UserService users = new UserService();
        users.register("u1", "Alice", "alice@example.com");
        users.register("u2", "Bob", "bob@example.com");
        OrderService orders = new OrderService(users);
        orders.setPrice("apple", 10.0);

        orders.createOrder("u1", Map.of("apple", 1));
        orders.createOrder("u2", Map.of("apple", 2));
        orders.createOrder("u1", Map.of("apple", 3));

        List<Order> u1Orders = orders.ordersOf("u1");
        assertEquals(2, u1Orders.size());
        assertEquals(10.0, u1Orders.get(0).total(), 0.0001);
        assertEquals(30.0, u1Orders.get(1).total(), 0.0001);
        assertTrue(orders.ordersOf("missing").isEmpty());
    }
}
