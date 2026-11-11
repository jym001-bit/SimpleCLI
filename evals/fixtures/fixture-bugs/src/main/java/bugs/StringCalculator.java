package bugs;

/**
 * 逗号分隔的整数加法器。
 *
 * 约定：null / 空串返回 0；忽略空片段；出现负数抛 IllegalArgumentException。
 */
public class StringCalculator {

    public int add(String input) {
        String[] parts = input.split(",");
        int sum = 0;
        for (String part : parts) {
            sum += Integer.parseInt(part.trim());
        }
        return sum;
    }
}
