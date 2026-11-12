package core.repo;

import core.model.Order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    void save(Order order);

    Optional<Order> find(String id);

    List<Order> all();
}
