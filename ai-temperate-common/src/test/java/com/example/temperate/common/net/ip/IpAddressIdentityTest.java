package com.example.temperate.common.net.ip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证数字 IP 字面量会被归一为稳定的地址族、二进制身份和 RFC 5952 展示文本。
 */
final class IpAddressIdentityTest {

    @Test
    void equivalentIpv6SpellingsShareOneIdentityAndCanonicalText() {
        IpAddressIdentity compressed = IpAddressIdentity.parse("2001:db8::1");
        IpAddressIdentity expanded = IpAddressIdentity.parse(
                "2001:0DB8:0000:0000:0000:0000:0000:0001");

        assertEquals(compressed, expanded);
        assertEquals(IpAddressIdentity.AddressFamily.IPV6, compressed.family());
        assertEquals("2001:db8::1", expanded.canonicalText());
        assertArrayEquals(compressed.hmacPayload(), expanded.hmacPayload());
        assertEquals(17, compressed.hmacPayload().length);
        assertEquals(0x06, Byte.toUnsignedInt(compressed.hmacPayload()[0]));
    }

    @Test
    void appliesRfc5952LongestRunAndFirstTieRules() {
        assertEquals("2001::1:0:0:1:1",
                IpAddressIdentity.parse("2001:0:0:1:0:0:1:1").canonicalText());
        assertEquals("::", IpAddressIdentity.parse("0:0:0:0:0:0:0:0").canonicalText());
    }

    @Test
    void normalizesIpv4MappedIpv6ToTheIpv4Identity() {
        IpAddressIdentity ipv4 = IpAddressIdentity.parse("192.0.2.1");
        IpAddressIdentity mapped = IpAddressIdentity.parse("::ffff:192.0.2.1");
        IpAddressIdentity mappedHex = IpAddressIdentity.parse("::FFFF:c000:0201");

        assertEquals(ipv4, mapped);
        assertEquals(ipv4, mappedHex);
        assertEquals(IpAddressIdentity.AddressFamily.IPV4, mapped.family());
        assertEquals("192.0.2.1", mapped.canonicalText());
        assertArrayEquals(new byte[] {0x04, (byte) 192, 0, 2, 1}, mapped.hmacPayload());
    }

    @Test
    void rejectsHostnamesZonesAndMalformedLiteralsWithoutDnsResolution() {
        List<String> invalid = List.of(
                "example.test",
                "fe80::1%eth0",
                "2001:db8::1::2",
                "192.0.2.1::",
                "1.2.3",
                "203.000.113.10",
                "1.2.3.999",
                "::ffff:example.test",
                "");

        invalid.forEach(value -> assertThrows(
                IllegalArgumentException.class,
                () -> IpAddressIdentity.parse(value)));
        assertThrows(IllegalArgumentException.class, () -> IpAddressIdentity.parse(null));
    }

    @Test
    void returnsDefensiveCopiesOfAddressAndHmacPayload() {
        IpAddressIdentity identity = IpAddressIdentity.parse("203.0.113.10");
        byte[] address = identity.addressBytes();
        byte[] payload = identity.hmacPayload();
        address[0] = 0;
        payload[1] = 0;

        assertArrayEquals(new byte[] {(byte) 203, 0, 113, 10}, identity.addressBytes());
        assertArrayEquals(
                new byte[] {0x04, (byte) 203, 0, 113, 10},
                identity.hmacPayload());
    }
}
