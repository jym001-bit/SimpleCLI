package core.api;

import core.model.Order;
import core.model.OrderLine;
import core.service.OrderService;

import java.util.List;

public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    public Order placeOrder(String id, String customerId, List<OrderLine> lines) {
        return orderService.createOrder(id, customerId, lines);
    }
}
