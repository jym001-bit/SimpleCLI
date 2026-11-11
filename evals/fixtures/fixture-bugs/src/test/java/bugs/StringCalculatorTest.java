package bugs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StringCalculatorTest {

    @Test
    void addsTwoNumbers() {
        assertEquals(3, new StringCalculator().add("1,2"));
    }

    @Test
    void nullReturnsZero() {
        assertEquals(0, new StringCalculator().add(null));
    }

    @Test
    void emptyStringReturnsZero() {
        assertEquals(0, new StringCalculator().add(""));
    }

    @Test
    void ignoresTrailingComma() {
        assertEquals(3, new StringCalculator().add("1,2,"));
    }

    @Test
    void negativeNumberRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StringCalculator().add("1,-2"));
    }
}
