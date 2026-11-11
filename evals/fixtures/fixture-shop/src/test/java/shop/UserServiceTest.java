package shop;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserServiceTest {

    @Test
    void registersAndFindsUser() {
        UserService service = new UserService();

        service.register("u1", "Alice", "alice@example.com");

        assertTrue(service.findById("u1").isPresent());
        assertEquals("Alice", service.findById("u1").orElseThrow().name());
    }

    @Test
    void rejectsInvalidEmail() {
        UserService service = new UserService();

        assertThrows(IllegalArgumentException.class,
                () -> service.register("u2", "Bob", "not-an-email"));
    }

    @Test
    void countsRegisteredUsers() {
        UserService service = new UserService();
        service.register("u1", "Alice", "alice@example.com");
        service.register("u2", "Bob", "bob@example.com");

        assertEquals(2, service.count());
    }
}
