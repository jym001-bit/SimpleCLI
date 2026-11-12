package shop;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * hard.v3 补充隐藏测试：堵住“只在 CartService 里打折”和“单 SKU 才算总数量”的走捷径实现。
 */
class HiddenBulkDiscountV3Test {

    @Test
    void discountsAcrossMultipleSkus() {
        OrderService orders = new OrderService(new UserService());
        orders.setPrice("apple", 10.0);
        orders.setPrice("pear", 5.0);
        CartService cart = new CartService(orders);
        cart.setDiscountPolicy(new DiscountPolicy());
        cart.addItem("apple", 5);
        cart.addItem("pear", 5);

        assertEquals(67.5, cart.total(), 0.0001);
    }

    @Test
    void keepsFullPriceAtNineItems() {
        OrderService orders = new OrderService(new UserService());
        orders.setPrice("apple", 10.0);
        CartService cart = new CartService(orders);
        cart.setDiscountPolicy(new DiscountPolicy());
        cart.addItem("apple", 9);

        assertEquals(90.0, cart.total(), 0.0001);
    }

    @Test
    void orderServiceCalculateTotalUsesPolicy() {
        OrderService orders = new OrderService(new UserService());
        orders.setPrice("apple", 10.0);
        orders.setDiscountPolicy(new DiscountPolicy());

        assertEquals(90.0, orders.calculateTotal(Map.of("apple", 10)), 0.0001);
    }
}
