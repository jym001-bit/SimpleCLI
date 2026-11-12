package core.repo;

import core.model.Order;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class InMemoryOrderRepository implements OrderRepository {

    private final Map<String, Order> orders = new LinkedHashMap<>();

    @Override
    public void save(Order order) {
        orders.put(order.id(), order);
    }

    @Override
    public Optional<Order> find(String id) {
        return Optional.ofNullable(orders.get(id));
    }

    @Override
    public List<Order> all() {
        return new ArrayList<>(orders.values());
    }
}
