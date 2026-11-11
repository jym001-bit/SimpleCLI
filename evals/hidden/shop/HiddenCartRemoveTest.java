package shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HiddenCartRemoveTest {

    @Test
    void removesExistingItem() {
        CartService cart = new CartService(new OrderService(new UserService()));
        cart.addItem("apple", 2);

        assertTrue(cart.removeItem("apple"));
        assertTrue(cart.items().isEmpty());
    }

    @Test
    void returnsFalseForMissingItem() {
        CartService cart = new CartService(new OrderService(new UserService()));

        assertFalse(cart.removeItem("apple"));
    }
}
