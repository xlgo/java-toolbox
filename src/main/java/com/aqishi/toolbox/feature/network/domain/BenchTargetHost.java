package com.aqishi.toolbox.feature.network.domain;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * 压测目标主机的归属分类，用于在开跑前区分"自己家的机器"和"别人的机器"。
 *
 * <p>压测本质上是可控的拒绝服务：同一段代码指着公网地址跑，测试自己的服务是基准测试，
 * 指向别人的服务就是攻击。分类只回答"这个地址看起来属于哪个范围"，判断依据是地址本身
 * （字面量直接判定，主机名走一次 DNS 解析），不做任何网络探测以外的动作。</p>
 *
 * <p>主机名解析失败时按 {@link Scope#PUBLIC} 处理——宁可多问一句，不要把未知目标当成本地。</p>
 */
public final class BenchTargetHost {

    /** 目标地址所处的范围。 */
    public enum Scope {
        /** 本机回环：127.0.0.0/8、::1、localhost。 */
        LOOPBACK,
        /** 链路本地：169.254.0.0/16、fe80::/10。 */
        LINK_LOCAL,
        /** 私有 / 内网 / 唯一本地地址：10/8、172.16/12、192.168/16、fc00::/7 等。 */
        PRIVATE,
        /** 组播、保留、运营商级 NAT（100.64/10）等特殊范围。 */
        RESERVED,
        /** 公网地址，或无法判定归属的主机名。 */
        PUBLIC;

        /** 是否属于"本机或内网"，这类目标不需要额外的授权确认；保留范围与公网一样要确认。 */
        public boolean isLocalOrPrivate() {
            return this == LOOPBACK || this == LINK_LOCAL || this == PRIVATE;
        }
    }

    private final String host;
    private final Scope scope;

    private BenchTargetHost(String host, Scope scope) {
        this.host = host;
        this.scope = scope;
    }

    /** 按主机名或 IP 字面量分类，必要时做一次 DNS 解析。 */
    public static BenchTargetHost of(String host) {
        String text = host == null ? "" : host.trim();
        if (text.startsWith("[") && text.endsWith("]")) {
            text = text.substring(1, text.length() - 1);
        }
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".")) {
            lower = lower.substring(0, lower.length() - 1);
        }
        if (lower.isEmpty()) {
            return new BenchTargetHost(text, Scope.PUBLIC);
        }
        if ("localhost".equals(lower) || "localhost.localdomain".equals(lower)
                || "ip6-localhost".equals(lower) || "ip6-loopback".equals(lower)) {
            return new BenchTargetHost(text, Scope.LOOPBACK);
        }
        byte[] literal = ipv4Literal(lower);
        if (literal != null) {
            return new BenchTargetHost(text, classify(literal));
        }
        int zone = lower.indexOf('%');
        String bare = zone >= 0 ? lower.substring(0, zone) : lower;
        if (bare.indexOf(':') >= 0) {
            byte[] v6 = isIpv6Literal(bare) ? ipv6Bytes(bare) : null;
            return new BenchTargetHost(text, v6 == null ? Scope.PUBLIC : classify(v6));
        }
        try {
            InetAddress[] resolved = InetAddress.getAllByName(text);
            if (resolved.length == 0) {
                return new BenchTargetHost(text, Scope.PUBLIC);
            }
            return new BenchTargetHost(text, combine(resolved));
        } catch (UnknownHostException unresolved) {
            return new BenchTargetHost(text, Scope.PUBLIC);
        }
    }

    /**
     * 多个解析结果合并成一个范围：任一地址需要确认（公网 / 保留），整体就取它；
     * 全部相同取该范围；本地与内网混杂时按内网算。
     */
    static Scope combine(InetAddress[] addresses) {
        Scope combined = null;
        for (InetAddress address : addresses) {
            Scope current = classify(address.getAddress());
            if (!current.isLocalOrPrivate()) {
                return current;
            }
            combined = combined == null || combined == current ? current : Scope.PRIVATE;
        }
        return combined == null ? Scope.PUBLIC : combined;
    }

    /** 直接取 URL 里的主机分类。 */
    public static BenchTargetHost of(URI uri) {
        return of(uri == null ? null : uri.getHost());
    }

    /** 按原始地址字节（4 或 16 字节）判定范围。 */
    public static Scope classify(byte[] address) {
        if (address == null || address.length == 0) {
            return Scope.PUBLIC;
        }
        if (address.length == 4) {
            int first = address[0] & 0xFF;
            int second = address[1] & 0xFF;
            if (first == 127) {
                return Scope.LOOPBACK;
            }
            if (first == 10) {
                return Scope.PRIVATE;
            }
            if (first == 172 && second >= 16 && second <= 31) {
                return Scope.PRIVATE;
            }
            if (first == 192 && second == 168) {
                return Scope.PRIVATE;
            }
            if (first == 169 && second == 254) {
                return Scope.LINK_LOCAL;
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return Scope.RESERVED;
            }
            if (first == 0 || first >= 224) {
                return Scope.RESERVED;
            }
            return Scope.PUBLIC;
        }
        if (address.length == 16) {
            if (isAllZero(address, 15) && (address[15] & 0xFF) == 1) {
                return Scope.LOOPBACK;
            }
            if (isAllZero(address, 15)) {
                return Scope.RESERVED;
            }
            if ((address[0] & 0xFF) == 0xFE && (address[1] & 0xC0) == 0x80) {
                return Scope.LINK_LOCAL;
            }
            if ((address[0] & 0xFE) == 0xFC) {
                return Scope.PRIVATE;
            }
            if ((address[0] & 0xFF) == 0xFF) {
                return Scope.RESERVED;
            }
            if (isIpv4Mapped(address)) {
                return classify(new byte[]{address[12], address[13], address[14], address[15]});
            }
            return Scope.PUBLIC;
        }
        return Scope.PUBLIC;
    }

    private static boolean isAllZero(byte[] address, int length) {
        for (int i = 0; i < length; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** ::ffff:a.b.c.d 映射地址按其内嵌的 IPv4 判定。 */
    private static boolean isIpv4Mapped(byte[] address) {
        if (!isAllZero(address, 10)) {
            return false;
        }
        return (address[10] & 0xFF) == 0xFF && (address[11] & 0xFF) == 0xFF;
    }

    /** 解析点分十进制 IPv4；只有严格的四段 0-255 才认，避免把域名当 IP。 */
    private static byte[] ipv4Literal(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !part.chars().allMatch(Character::isDigit)) {
                return null;
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                return null;
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static boolean isIpv6Literal(String text) {
        return text.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                || c == ':' || c == '.');
    }

    /**
     * 解析 IPv6 字面量。{@link InetAddress#getByName} 对含冒号的字面量只做语法解析、不查 DNS；
     * 区域标识（{@code %eth0}）先去掉，否则会去查网卡。解析不了时返回 null。
     */
    private static byte[] ipv6Bytes(String text) {
        try {
            return InetAddress.getByName(text).getAddress();
        } catch (UnknownHostException | RuntimeException invalid) {
            return null;
        }
    }

    /** 原始主机名 / IP 文本。 */
    public String host() {
        return host;
    }

    public Scope scope() {
        return scope;
    }

    public boolean requiresAuthorizationWarning() {
        return !scope.isLocalOrPrivate();
    }

    @Override
    public String toString() {
        return host + " (" + scope + ")";
    }
}
