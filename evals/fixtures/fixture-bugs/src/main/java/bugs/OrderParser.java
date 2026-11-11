package bugs;

/**
 * 解析 "price:12.5" 这样的价格行。
 *
 * 约定：格式不合法或价格为空时抛 IllegalArgumentException。
 */
public class OrderParser {

    public double parsePrice(String line) {
        String value = line.split(":")[1];
        return Double.parseDouble(value);
    }
}
