package shop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartServiceTest {

    @Test
    void accumulatesSameSku() {
        CartService cart = new CartService(new OrderService(new UserService()));

        cart.addItem("apple", 2);
        cart.addItem("apple", 3);

        assertEquals(5, cart.items().get("apple"));
    }

    @Test
    void totalUsesOrderServicePricing() {
        OrderService orders = new OrderService(new UserService());
        orders.setPrice("apple", 3.5);
        CartService cart = new CartService(orders);

        cart.addItem("apple", 2);

        assertEquals(7.0, cart.total(), 0.0001);
    }

    @Test
    void checkoutCreatesOrderAndClearsCart() {
        UserService users = new UserService();
        users.register("u1", "Alice", "alice@example.com");
        OrderService orders = new OrderService(users);
        orders.setPrice("apple", 3.5);
        CartService cart = new CartService(orders);
        cart.addItem("apple", 1);

        Order order = cart.checkout("u1");

        assertEquals(3.5, order.total(), 0.0001);
        assertTrue(cart.items().isEmpty());
    }
}
