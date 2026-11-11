package bugs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryTest {

    @Test
    void addsStock() {
        Inventory inventory = new Inventory();
        inventory.add("apple", 3);
        inventory.add("apple", 2);

        assertEquals(5, inventory.stockOf("apple"));
    }

    @Test
    void unknownSkuHasZeroStock() {
        assertEquals(0, new Inventory().stockOf("missing"));
    }

    @Test
    void reportsAvailability() {
        Inventory inventory = new Inventory();
        inventory.add("apple", 3);

        assertTrue(inventory.isAvailable("apple", 3));
        assertFalse(inventory.isAvailable("apple", 4));
    }
}
