package bugs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * hard.v3 补充隐藏测试：堵住“只处理末尾逗号”“价格为空时返回 0”的半成品修复。
 */
class HiddenBugsContractV3Test {

    @Test
    void calculatorIgnoresEmptySegmentsInTheMiddle() {
        assertEquals(3, new StringCalculator().add("1,,2"));
    }

    @Test
    void parserRejectsEmptyPrice() {
        assertThrows(IllegalArgumentException.class, () -> new OrderParser().parsePrice("price:"));
    }

    @Test
    void parserRejectsNonNumericPrice() {
        assertThrows(IllegalArgumentException.class, () -> new OrderParser().parsePrice("price:abc"));
    }
}
