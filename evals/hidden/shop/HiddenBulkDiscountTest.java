package shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HiddenBulkDiscountTest {

    @Test
    void appliesTenPercentDiscountAtTenItems() {
        OrderService orders = new OrderService(new UserService());
        orders.setPrice("apple", 10.0);
        CartService cart = new CartService(orders);
        cart.setDiscountPolicy(new DiscountPolicy());
        cart.addItem("apple", 10);

        assertEquals(90.0, cart.total(), 0.0001);
    }

    @Test
    void keepsFullPriceBelowTenItems() {
        OrderService orders = new OrderService(new UserService());
        orders.setPrice("apple", 10.0);
        CartService cart = new CartService(orders);
        cart.setDiscountPolicy(new DiscountPolicy());
        cart.addItem("apple", 5);

        assertEquals(50.0, cart.total(), 0.0001);
    }
}