package com.example.temperate.common.codec.id;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 在 16 字节混合 ID、十六进制文本与 Base62 公共文本之间进行规范转换。
 *
 * <p>该工具只处理表示层转换，不生成 ID；数据库驱动返回的二进制或十六进制值必须先归一化，避免
 * 不同表示形式在 API 边界产生多个等价标识。</p>
 */
public final class HybridIdCodec {

    public static final String BASE62_PATTERN = "^[0-9A-Za-z]{1,22}$";

    private static final int ID_BYTES = 16;
    private static final char[] BASE62_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int[] BASE62_INDEX = new int[128];
    private static final BigInteger BASE62_RADIX = BigInteger.valueOf(BASE62_ALPHABET.length);

    static {
        Arrays.fill(BASE62_INDEX, -1);
        for (int index = 0; index < BASE62_ALPHABET.length; index += 1) {
            BASE62_INDEX[BASE62_ALPHABET[index]] = index;
        }
    }

    private HybridIdCodec() {
    }

    public static String toBase62(byte[] bytes) {
        byte[] normalized = normalizeBytes(bytes);
        BigInteger value = new BigInteger(1, normalized);
        if (BigInteger.ZERO.equals(value)) {
            return "0";
        }
        StringBuilder builder = new StringBuilder();
        while (value.compareTo(BigInteger.ZERO) > 0) {
            BigInteger[] divideAndRemainder = value.divideAndRemainder(BASE62_RADIX);
            builder.append(BASE62_ALPHABET[divideAndRemainder[1].intValue()]);
            value = divideAndRemainder[0];
        }
        return builder.reverse().toString();
    }

    public static byte[] fromBase62(String value) {
        if (value == null || !value.matches(BASE62_PATTERN)) {
            throw new IllegalArgumentException("Hybrid ID must be Base62.");
        }
        BigInteger decoded = BigInteger.ZERO;
        for (int index = 0; index < value.length(); index += 1) {
            char ch = value.charAt(index);
            int digit = ch < BASE62_INDEX.length ? BASE62_INDEX[ch] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("Hybrid ID must be Base62.");
            }
            decoded = decoded.multiply(BASE62_RADIX).add(BigInteger.valueOf(digit));
        }
        byte[] raw = decoded.toByteArray();
        if (raw.length > ID_BYTES + 1 || (raw.length == ID_BYTES + 1 && raw[0] != 0)) {
            throw new IllegalArgumentException("Hybrid ID exceeds 16 bytes.");
        }
        byte[] normalized = new byte[ID_BYTES];
        int sourceStart = raw.length > ID_BYTES ? raw.length - ID_BYTES : 0;
        int copyLength = raw.length - sourceStart;
        System.arraycopy(raw, sourceStart, normalized, ID_BYTES - copyLength, copyLength);
        return normalized;
    }

    public static String toHex(byte[] bytes) {
        return HexFormat.of().formatHex(normalizeBytes(bytes));
    }

    public static byte[] fromHex(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.startsWith("\\x") || normalized.startsWith("\\X")) {
            normalized = normalized.substring(2);
        }
        if (!normalized.matches("^[0-9A-Fa-f]{32}$")) {
            throw new IllegalArgumentException("Hybrid ID hex must be 16 bytes.");
        }
        return HexFormat.of().parseHex(normalized);
    }

    public static String hexToBase62(String value) {
        return toBase62(fromHex(value));
    }

    public static String toBase62FromDatabaseValue(Object raw) {
        // PostgreSQL/JDBC 可能返回 byte[]、\\x 前缀十六进制或已编码文本；在这一边界统一为 Base62。
        if (raw instanceof byte[] bytes) {
            return toBase62(bytes);
        }
        String value = raw == null ? "" : String.valueOf(raw).trim();
        if (value.startsWith("\\x") || value.startsWith("\\X")) {
            value = value.substring(2);
        }
        if (value.matches("^[0-9A-Fa-f]{32}$")) {
            return hexToBase62(value);
        }
        return value.matches(BASE62_PATTERN) ? value : "";
    }

    private static byte[] normalizeBytes(byte[] bytes) {
        if (bytes == null || bytes.length != ID_BYTES) {
            throw new IllegalArgumentException("Hybrid ID must be 16 bytes.");
        }
        return bytes;
    }
}
