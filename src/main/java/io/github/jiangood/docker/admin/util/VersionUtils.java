package io.github.jiangood.docker.admin.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 版本号（tag）排序工具：按「数字段 / 非数字段」分段做自然比较。
 * <p>
 * 纯字符串排序对多位版本段会排错（如 {@code v1.9.0} 会排在 {@code v1.10.0} 之后），
 * 这里把 tag 拆成数字与非数字片段逐段比较，数字段按数值比较，因此
 * {@code v1.10.0 > v1.9.0 > v1.2.0}。非数字 tag（如 {@code latest}）不含数字，
 * 统一排在数字版本之后，方便就近选择最新版本。
 */
public final class VersionUtils {

    /**
     * 版本倒序：数字版本在前且按数值从大到小，非数字 tag（如 latest）在最后。
     */
    public static final Comparator<String> VERSION_DESC = (a, b) -> {
        int c = compareAsc(a, b);
        if (c != 0) {
            return -c;
        }
        // 语义相等（如 01 与 1）时再按字符串倒序，保证顺序稳定
        return b.compareTo(a);
    };

    private VersionUtils() {
    }

    /**
     * 自然升序比较；仅用于推导倒序，不要在业务里直接使用。
     */
    static int compareAsc(String a, String b) {
        boolean digitA = containsDigit(a);
        boolean digitB = containsDigit(b);
        if (digitA != digitB) {
            // 升序时非数字在前，倒序后即数字版本在前、latest 在后
            return digitA ? 1 : -1;
        }

        List<String> ta = tokenize(a);
        List<String> tb = tokenize(b);
        int size = Math.min(ta.size(), tb.size());
        for (int i = 0; i < size; i++) {
            String x = ta.get(i);
            String y = tb.get(i);
            boolean nx = isNumeric(x);
            boolean ny = isNumeric(y);
            int c = (nx && ny) ? compareNumeric(x, y) : x.compareTo(y);
            if (c != 0) {
                return c;
            }
        }
        if (ta.size() == tb.size()) {
            return 0;
        }
        // 一方是另一方的前缀：正常情况下段数越多越大；
        // 但多出的那段以 '-' 开头说明是预发布版本（如 1.9.0-rc1），应小于正式版 1.9.0
        int lenCmp = Integer.compare(ta.size(), tb.size());
        String extra = ta.size() > tb.size() ? ta.get(size) : tb.get(size);
        if (!isNumeric(extra) && extra.startsWith("-")) {
            return -lenCmp;
        }
        return lenCmp;
    }

    private static boolean containsDigit(String value) {
        if (value == null) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isDigit(value.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 拆成交替的数字段与非数字段，如 {@code v1.10.0} → [v, 1, ., 10, ., 0]。
     */
    private static List<String> tokenize(String value) {
        List<String> tokens = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return tokens;
        }
        StringBuilder sb = new StringBuilder();
        boolean digit = Character.isDigit(value.charAt(0));
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean d = Character.isDigit(c);
            if (d != digit) {
                tokens.add(sb.toString());
                sb.setLength(0);
                digit = d;
            }
            sb.append(c);
        }
        tokens.add(sb.toString());
        return tokens;
    }

    private static boolean isNumeric(String token) {
        if (token.isEmpty()) {
            return false;
        }
        for (int i = 0; i < token.length(); i++) {
            if (!Character.isDigit(token.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 数字段比较：去掉前导零后先比长度再比字典序，避免大数溢出。
     */
    private static int compareNumeric(String x, String y) {
        String a = stripLeadingZeros(x);
        String b = stripLeadingZeros(y);
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return a.compareTo(b);
    }

    private static String stripLeadingZeros(String value) {
        int i = 0;
        while (i < value.length() - 1 && value.charAt(i) == '0') {
            i++;
        }
        return value.substring(i);
    }
}
