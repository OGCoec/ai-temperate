package com.example.temperate.common.codec.id;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * 该编解码器是来把 Hybrid Worker 生成的 16 字节资源 ID 转换为固定 26 字符规范 ULID。
 *
 * <p>ULID 只作为 API Key 的公开表示，不提供加密或授权；解码后仍必须执行当前用户的资源级所有权校验。</p>
 */
public final class HybridUlidCodec {

    public static final int BINARY_LENGTH = 16;
    public static final int ENCODED_LENGTH = 26;
    public static final String ENCODED_PATTERN = "^[0-7][0-9A-HJKMNP-TV-Z]{25}$";
    public static final String FORMAT = ENCODED_PATTERN;

    private static final char[] ALPHABET =
            "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int[] DECODE = new int[128];
    private static final BigInteger RADIX = BigInteger.valueOf(32L);
    private static final Pattern FORMAT_PATTERN = Pattern.compile(ENCODED_PATTERN);

    static {
        Arrays.fill(DECODE, -1);
        for (int index = 0; index < ALPHABET.length; index += 1) {
            DECODE[ALPHABET[index]] = index;
        }
    }

    /**
     * 将非零 128 位 ID 编码为固定长度大写 Crockford Base32，固定长度保证文本排序与二进制排序一致。
     */
    public String encode(byte[] value) {
        byte[] normalized = requireBinary(value);
        BigInteger remaining = new BigInteger(1, normalized);
        if (BigInteger.ZERO.equals(remaining)) {
            throw new IllegalArgumentException("Hybrid ULID must not be zero.");
        }
        char[] encoded = new char[ENCODED_LENGTH];
        for (int index = encoded.length - 1; index >= 0; index -= 1) {
            BigInteger[] division = remaining.divideAndRemainder(RADIX);
            encoded[index] = ALPHABET[division[1].intValue()];
            remaining = division[0];
        }
        if (!BigInteger.ZERO.equals(remaining) || encoded[0] > '7') {
            throw new IllegalArgumentException("Hybrid ULID exceeds 128 bits.");
        }
        return new String(encoded);
    }

    /**
     * 解码并回编码校验规范 ULID，拒绝大小写别名、歧义字符和同一二进制值的非规范文本表示。
     */
    public byte[] decode(String value) {
        if (value == null
                || value.length() != ENCODED_LENGTH
                || !FORMAT_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Hybrid ULID must be a canonical 26-character value.");
        }
        BigInteger decoded = BigInteger.ZERO;
        for (int index = 0; index < value.length(); index += 1) {
            char character = value.charAt(index);
            int digit = character < DECODE.length ? DECODE[character] : -1;
            if (digit < 0) {
                throw new IllegalArgumentException("Hybrid ULID contains an invalid character.");
            }
            decoded = decoded.multiply(RADIX).add(BigInteger.valueOf(digit));
        }
        if (decoded.signum() <= 0 || decoded.bitLength() > BINARY_LENGTH * Byte.SIZE) {
            throw new IllegalArgumentException("Hybrid ULID is outside the supported range.");
        }
        byte[] raw = decoded.toByteArray();
        byte[] normalized = new byte[BINARY_LENGTH];
        int sourceOffset = Math.max(0, raw.length - BINARY_LENGTH);
        int copyLength = raw.length - sourceOffset;
        System.arraycopy(raw, sourceOffset, normalized, BINARY_LENGTH - copyLength, copyLength);
        if (!encode(normalized).equals(value)) {
            throw new IllegalArgumentException("Hybrid ULID is not canonically encoded.");
        }
        return normalized;
    }

    private static byte[] requireBinary(byte[] value) {
        if (value == null || value.length != BINARY_LENGTH) {
            throw new IllegalArgumentException("Hybrid ULID must contain exactly 16 bytes.");
        }
        return value.clone();
    }
}
