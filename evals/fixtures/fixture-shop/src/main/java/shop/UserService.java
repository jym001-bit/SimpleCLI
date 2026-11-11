package shop;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

public class UserService {

    private final Map<String, User> users = new LinkedHashMap<>();

    public User register(String id, String name, String email) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id is required");
        }
        if (email == null || !email.contains("@")) {
            throw new IllegalArgumentException("invalid email: " + email);
        }
        User user = new User(id, name, email);
        users.put(id, user);
        return user;
    }

    public Optional<User> findById(String id) {
        return Optional.ofNullable(users.get(id));
    }

    public int count() {
        return users.size();
    }
}
