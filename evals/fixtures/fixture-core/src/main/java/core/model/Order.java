package core.model;

import java.util.List;

public record Order(String id, String customerId, List<OrderLine> lines, long totalCents) {
}
