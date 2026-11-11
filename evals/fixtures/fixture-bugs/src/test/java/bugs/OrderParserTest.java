package bugs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OrderParserTest {

    @Test
    void parsesPrice() {
        assertEquals(12.5, new OrderParser().parsePrice("price:12.5"), 0.0001);
    }

    @Test
    void rejectsMissingSeparator() {
        assertThrows(IllegalArgumentException.class, () -> new OrderParser().parsePrice("price12.5"));
    }

    @Test
    void rejectsNegativePrice() {
        assertThrows(IllegalArgumentException.class, () -> new OrderParser().parsePrice("price:-1"));
    }
}
