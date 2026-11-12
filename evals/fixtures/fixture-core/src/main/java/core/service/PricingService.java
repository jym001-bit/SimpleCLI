package core.service;

import core.model.OrderLine;
import core.model.Product;
import core.util.Money;

import java.util.List;
import java.util.Map;

public class PricingService {

    public long subtotal(List<OrderLine> lines, Map<String, Product> products) {
        long subtotal = 0;
        for (OrderLine line : lines) {
            Product product = products.get(line.productId());
            if (product == null) {
                throw new IllegalArgumentException("unknown product: " + line.productId());
            }
            subtotal += product.priceCents() * line.quantity();
        }
        return subtotal;
    }

    public long applyBulkDiscount(long subtotalCents, int totalQuantity) {
        if (totalQuantity >= 10) {
            return subtotalCents - Money.round(subtotalCents * 0.10);
        }
        return subtotalCents;
    }

    public long total(List<OrderLine> lines, Map<String, Product> products) {
        int quantity = lines.stream().mapToInt(OrderLine::quantity).sum();
        return applyBulkDiscount(subtotal(lines, products), quantity);
    }
}
