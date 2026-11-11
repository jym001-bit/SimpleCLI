package shop;

import java.util.Map;

public record Order(String id, String userId, Map<String, Integer> items, double total) {
}
