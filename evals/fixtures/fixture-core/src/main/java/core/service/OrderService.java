package core.service;

import core.model.Order;
import core.model.OrderLine;
import core.model.Product;
import core.repo.OrderRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OrderService {

    private final OrderRepository repository;
    private final PricingService pricing;
    private final Map<String, Product> products = new HashMap<>();

    public OrderService(OrderRepository repository, PricingService pricing) {
        this.repository = repository;
        this.pricing = pricing;
    }

    public void addProduct(Product product) {
        products.put(product.id(), product);
    }

    public Order createOrder(String id, String customerId, List<OrderLine> lines) {
        long total = pricing.subtotal(lines, products);
        Order order = new Order(id, customerId, List.copyOf(lines), total);
        repository.save(order);
        return order;
    }
}
