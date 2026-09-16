package com.aqishi.toolbox.feature.network.domain;

import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DNS 解析、TLS 握手与 HTTP 分段耗时的诊断服务。
 *
 * <p>「接口怎么这么慢」和「证书到底哪里不对」这两类问题，靠一次请求的总耗时和一句
 * handshake failure 是查不出来的。这里把一次访问拆成可以分别归因的阶段：
 * 域名解析、TCP 连接、TLS 握手、首字节、内容传输，并把证书链摊开到可逐张核对。</p>
 *
 * <p>所有方法都是阻塞的，调用方负责放到后台线程并自行控制超时上限。
 * 本类不产出面向用户的文案，错误以原始描述返回，由界面层决定呈现方式。</p>
 */
public final class NetDiagnosticsService {

    /** JNDI 的 DNS 提供方。按名字引用，避免对内部实现产生编译期依赖。 */
    private static final String DNS_CONTEXT_FACTORY = "com.sun.jndi.dns.DnsContextFactory";

    /** 可查询的记录类型，按常用度排序。 */
    public static final List<String> RECORD_TYPES =
            List.of("A", "AAAA", "CNAME", "MX", "TXT", "NS", "SOA", "SRV", "CAA", "PTR");

    /** 默认勾选的记录类型：绝大多数排查从这三类开始。 */
    public static final List<String> DEFAULT_RECORD_TYPES = List.of("A", "AAAA", "CNAME");

    /** 一条 DNS 记录。{@code source} 标明来自哪台解析器或系统解析栈。 */
    public record DnsRecord(String type, String value, String source) {
    }

    /** DNS 查询结果。{@code error} 非空表示整轮查询失败。 */
    public record DnsResult(String host, List<DnsRecord> records, long elapsedMs, String error) {
        public boolean isSuccess() {
            return error == null;
        }
    }

    /** 证书链里的一张证书。 */
    public record CertificateInfo(int index, String subject, String issuer, String serialNumber,
                                  Instant notBefore, Instant notAfter, long daysRemaining,
                                  String signatureAlgorithm, String publicKeyAlgorithm,
                                  int publicKeyBits, List<String> subjectAlternativeNames,
                                  String sha256Fingerprint, boolean selfSigned) {
        public boolean isExpired() {
            return daysRemaining < 0;
        }
    }

    /** TLS 握手结果。 */
    public record TlsResult(String host, int port, String protocol, String cipherSuite,
                            String sniServerName, boolean trusted, String trustError,
                            boolean hostnameMatched, List<CertificateInfo> chain,
                            long handshakeMs, String error) {
        public boolean isSuccess() {
            return error == null;
        }
    }

    /** 重定向链上的一跳。 */
    public record HttpHop(int statusCode, String url, String location, long elapsedMs) {
    }

    /** HTTP 分段耗时结果。各阶段单位均为毫秒，未经历的阶段为 -1。 */
    public record HttpResult(String url, int statusCode, String httpVersion, long dnsMs,
                             long tcpMs, long tlsMs, long ttfbMs, long transferMs, long totalMs,
                             long contentLength, String remoteAddress,
                             List<HttpHop> redirects, Map<String, List<String>> headers,
                             String error) {
        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * 查询 DNS 记录。
     *
     * @param host      待查询的域名
     * @param types     记录类型，空则使用 {@link #DEFAULT_RECORD_TYPES}
     * @param server    指定 DNS 服务器地址，留空走系统默认解析器
     * @param timeoutMs 单次查询超时
     */
    public DnsResult lookup(String host, Collection<String> types, String server, int timeoutMs) {
        String target = host == null ? "" : host.trim();
        if (target.isEmpty()) {
            return new DnsResult(target, List.of(), 0L, "empty.host");
        }
        List<String> wanted = types == null || types.isEmpty()
                ? DEFAULT_RECORD_TYPES : new ArrayList<>(types);
        long started = System.nanoTime();
        List<DnsRecord> records = new ArrayList<>();
        String error = null;

        String resolver = server == null ? "" : server.trim();
        try {
            records.addAll(queryViaJndi(target, wanted, resolver, timeoutMs));
        } catch (NamingException namingError) {
            error = describe(namingError);
        } catch (Exception providerMissing) {
            // jdk.naming.dns 不在运行时镜像里时退回系统解析栈，至少给出 A/AAAA。
            error = describe(providerMissing);
        }

        // 系统解析栈的结果单独列出：它反映的是本机 hosts + 解析器的最终行为，
        // 与直接问某台 DNS 服务器未必一致，而这种不一致恰恰是要找的东西。
        if (resolver.isEmpty()) {
            try {
                for (InetAddress address : InetAddress.getAllByName(target)) {
                    records.add(new DnsRecord(
                            address.getAddress().length == 4 ? "A" : "AAAA",
                            address.getHostAddress(), "system"));
                }
                error = null;
            } catch (Exception resolveFailed) {
                if (error == null) {
                    error = describe(resolveFailed);
                }
            }
        }

        long elapsed = elapsedMs(started);
        if (records.isEmpty() && error == null) {
            error = "no.records";
        }
        return new DnsResult(target, Collections.unmodifiableList(records), elapsed,
                records.isEmpty() ? error : null);
    }

    /** 反向解析：由 IP 查 PTR 记录。 */
    public DnsResult reverseLookup(String ip, String server, int timeoutMs) {
        String address = ip == null ? "" : ip.trim();
        if (address.isEmpty()) {
            return new DnsResult(address, List.of(), 0L, "empty.host");
        }
        long started = System.nanoTime();
        try {
            String arpa = toArpaName(address);
            List<DnsRecord> records = new ArrayList<>(
                    queryViaJndi(arpa, List.of("PTR"), server == null ? "" : server.trim(), timeoutMs));
            if (records.isEmpty()) {
                String name = InetAddress.getByName(address).getCanonicalHostName();
                if (!name.equals(address)) {
                    records.add(new DnsRecord("PTR", name, "system"));
                }
            }
            return new DnsResult(address, Collections.unmodifiableList(records), elapsedMs(started),
                    records.isEmpty() ? "no.records" : null);
        } catch (Exception error) {
            return new DnsResult(address, List.of(), elapsedMs(started), describe(error));
        }
    }

    /**
     * 握手并解析服务端证书链。
     *
     * <p>默认信任库校验失败时会再握一次手，只为把证书链取回来给人看——
     * 结果里 {@code trusted=false} 且带上失败原因，不会把「取到了链」说成「验证通过」。</p>
     */
    public TlsResult inspectTls(String host, int port, String sniName, int timeoutMs) {
        String target = host == null ? "" : host.trim();
        if (target.isEmpty()) {
            return new TlsResult(target, port, null, null, null, false, null, false,
                    List.of(), 0L, "empty.host");
        }
        String sni = sniName == null || sniName.trim().isEmpty() ? target : sniName.trim();
        long started = System.nanoTime();
        try {
            return handshake(target, port, sni, timeoutMs, null, true, started);
        } catch (Exception strictFailure) {
            String trustError = describe(strictFailure);
            try {
                TlsResult permissive = handshake(target, port, sni, timeoutMs,
                        trustAllContext(), false, started);
                return new TlsResult(permissive.host(), permissive.port(), permissive.protocol(),
                        permissive.cipherSuite(), permissive.sniServerName(), false, trustError,
                        hostnameMatches(target, permissive.chain()), permissive.chain(),
                        permissive.handshakeMs(), null);
            } catch (Exception connectFailure) {
                return new TlsResult(target, port, null, null, sni, false, trustError, false,
                        List.of(), elapsedMs(started), describe(connectFailure));
            }
        }
    }

    /**
     * 分阶段测量一次 HTTP 请求。
     *
     * <p>解析、连接、握手三段由一条独立的探测连接测得，随后的请求走 {@link HttpClient}——
     * 标准客户端不暴露连接内部的时间点，硬拼一个 HTTP 客户端换来的精度不值当。
     * 因此这三段是同一目标的同期样本，而非本次请求所用连接的实测值。</p>
     *
     * @param maxRedirects 最多跟随的重定向跳数，0 表示不跟随
     */
    public HttpResult probeHttp(String rawUrl, String method, int timeoutMs, int maxRedirects) {
        String input = rawUrl == null ? "" : rawUrl.trim();
        if (input.isEmpty()) {
            return errorResult(input, "empty.url");
        }
        if (!input.contains("://")) {
            input = "https://" + input;
        }
        URI uri;
        try {
            uri = URI.create(input);
        } catch (Exception badUrl) {
            return errorResult(input, describe(badUrl));
        }
        String host = uri.getHost();
        if (host == null) {
            return errorResult(input, "invalid.url");
        }
        boolean secure = "https".equalsIgnoreCase(uri.getScheme());
        int port = uri.getPort() > 0 ? uri.getPort() : (secure ? 443 : 80);

        long totalStarted = System.nanoTime();
        long dnsMs = -1L;
        long tcpMs = -1L;
        long tlsMs = -1L;
        String remoteAddress = "";
        try {
            long dnsStarted = System.nanoTime();
            InetAddress resolved = InetAddress.getByName(host);
            dnsMs = elapsedMs(dnsStarted);
            remoteAddress = resolved.getHostAddress();

            long tcpStarted = System.nanoTime();
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(resolved, port), timeoutMs);
                tcpMs = elapsedMs(tcpStarted);
                if (secure) {
                    long tlsStarted = System.nanoTime();
                    try (SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(socket, host, port, false)) {
                        tls.setSoTimeout(timeoutMs);
                        applySni(tls, host, false);
                        tls.startHandshake();
                        tlsMs = elapsedMs(tlsStarted);
                    }
                }
            }
        } catch (Exception probeFailed) {
            return new HttpResult(input, 0, null, dnsMs, tcpMs, tlsMs, -1L, -1L,
                    elapsedMs(totalStarted), -1L, remoteAddress, List.of(), Map.of(),
                    describe(probeFailed));
        }

        List<HttpHop> redirects = new ArrayList<>();
        // 重定向自己跟：标准客户端跟随重定向后只会报告最后一跳，中间的状态码和 Location 就看不到了。
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        try {
            URI current = uri;
            String verb = method == null || method.trim().isEmpty()
                    ? "GET" : method.trim().toUpperCase(Locale.ROOT);
            for (int hop = 0; ; hop++) {
                long requestStarted = System.nanoTime();
                HttpRequest request = HttpRequest.newBuilder(current)
                        .timeout(Duration.ofMillis(timeoutMs))
                        .method(verb, HttpRequest.BodyPublishers.noBody())
                        .build();
                HttpResponse<InputStream> response =
                        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                long ttfbMs = elapsedMs(requestStarted);

                long transferStarted = System.nanoTime();
                long bytes = drain(response.body());
                long transferMs = elapsedMs(transferStarted);

                String location = response.headers().firstValue("location").orElse("");
                boolean redirect = response.statusCode() >= 300 && response.statusCode() < 400
                        && !location.isEmpty();
                if (redirect && hop < maxRedirects) {
                    redirects.add(new HttpHop(response.statusCode(), current.toString(), location,
                            ttfbMs + transferMs));
                    current = current.resolve(location);
                    continue;
                }
                return new HttpResult(current.toString(), response.statusCode(),
                        String.valueOf(response.version()), dnsMs, tcpMs, tlsMs, ttfbMs, transferMs,
                        elapsedMs(totalStarted), bytes, remoteAddress,
                        Collections.unmodifiableList(redirects), response.headers().map(), null);
            }
        } catch (Exception requestFailed) {
            return new HttpResult(input, 0, null, dnsMs, tcpMs, tlsMs, -1L, -1L,
                    elapsedMs(totalStarted), -1L, remoteAddress,
                    Collections.unmodifiableList(redirects), Map.of(), describe(requestFailed));
        }
    }

    // ==========================================
    // DNS
    // ==========================================

    private List<DnsRecord> queryViaJndi(String host, List<String> types, String server, int timeoutMs)
            throws NamingException {
        Hashtable<String, String> environment = new Hashtable<>();
        environment.put(javax.naming.Context.INITIAL_CONTEXT_FACTORY, DNS_CONTEXT_FACTORY);
        environment.put(javax.naming.Context.PROVIDER_URL,
                server.isEmpty() ? "dns:" : "dns://" + server);
        environment.put("com.sun.jndi.dns.timeout.initial", String.valueOf(Math.max(500, timeoutMs)));
        environment.put("com.sun.jndi.dns.timeout.retries", "2");

        List<DnsRecord> records = new ArrayList<>();
        DirContext context = new InitialDirContext(environment);
        try {
            String source = server.isEmpty() ? "dns" : server;
            NamingException lastFailure = null;
            int failures = 0;
            // 必须一次只问一种记录类型：JNDI 的 DNS 提供方对多类型请求一律返回空属性集，
            // 既不报错也不返回记录，问 {A, MX} 的结果是什么都拿不到。
            for (String type : types) {
                try {
                    Attributes attributes = context.getAttributes(host, new String[]{type});
                    Attribute attribute = attributes.get(type);
                    if (attribute == null) {
                        continue;
                    }
                    for (int i = 0; i < attribute.size(); i++) {
                        Object value = attribute.get(i);
                        if (value != null) {
                            records.add(new DnsRecord(type, String.valueOf(value), source));
                        }
                    }
                } catch (NamingException typeFailed) {
                    // 有的服务器对个别类型回 NOTIMP，不该因此丢掉其余类型的结果。
                    failures++;
                    lastFailure = typeFailed;
                }
            }
            if (records.isEmpty() && failures == types.size() && lastFailure != null) {
                throw lastFailure;
            }
        } finally {
            closeQuietly(context);
        }
        return records;
    }

    private static String toArpaName(String address) throws Exception {
        InetAddress parsed = InetAddress.getByName(address);
        byte[] raw = parsed.getAddress();
        StringBuilder name = new StringBuilder();
        if (raw.length == 4) {
            for (int i = raw.length - 1; i >= 0; i--) {
                name.append(raw[i] & 0xFF).append('.');
            }
            return name.append("in-addr.arpa").toString();
        }
        for (int i = raw.length - 1; i >= 0; i--) {
            name.append(Character.forDigit(raw[i] & 0xF, 16)).append('.');
            name.append(Character.forDigit((raw[i] >> 4) & 0xF, 16)).append('.');
        }
        return name.append("ip6.arpa").toString();
    }

    // ==========================================
    // TLS
    // ==========================================

    private TlsResult handshake(String host, int port, String sni, int timeoutMs,
                                SSLContext context, boolean verifyHostname, long started)
            throws Exception {
        SSLSocketFactory factory = context == null
                ? (SSLSocketFactory) SSLSocketFactory.getDefault() : context.getSocketFactory();
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), timeoutMs);
            try (SSLSocket socket = (SSLSocket) factory.createSocket(plain, host, port, false)) {
                socket.setSoTimeout(timeoutMs);
                applySni(socket, sni, verifyHostname);
                socket.startHandshake();
                SSLSession session = socket.getSession();
                List<CertificateInfo> chain = describeChain(session.getPeerCertificates());
                return new TlsResult(host, port, session.getProtocol(), session.getCipherSuite(),
                        sni, true, null, verifyHostname || hostnameMatches(host, chain), chain,
                        elapsedMs(started), null);
            }
        }
    }

    private static void applySni(SSLSocket socket, String sni, boolean verifyHostname) {
        SSLParameters parameters = socket.getSSLParameters();
        if (sni != null && !sni.isEmpty() && !isIpLiteral(sni)) {
            List<SNIServerName> names = new ArrayList<>();
            names.add(new SNIHostName(sni));
            parameters.setServerNames(names);
        }
        if (verifyHostname) {
            parameters.setEndpointIdentificationAlgorithm("HTTPS");
        }
        socket.setSSLParameters(parameters);
    }

    private List<CertificateInfo> describeChain(Certificate[] certificates) {
        List<CertificateInfo> chain = new ArrayList<>();
        for (int i = 0; certificates != null && i < certificates.length; i++) {
            if (!(certificates[i] instanceof X509Certificate certificate)) {
                continue;
            }
            Instant notBefore = certificate.getNotBefore().toInstant();
            Instant notAfter = certificate.getNotAfter().toInstant();
            chain.add(new CertificateInfo(i,
                    certificate.getSubjectX500Principal().getName(),
                    certificate.getIssuerX500Principal().getName(),
                    certificate.getSerialNumber().toString(16).toUpperCase(Locale.ROOT),
                    notBefore, notAfter,
                    ChronoUnit.DAYS.between(Instant.now(), notAfter),
                    certificate.getSigAlgName(),
                    certificate.getPublicKey().getAlgorithm(),
                    publicKeyBits(certificate),
                    subjectAlternativeNames(certificate),
                    fingerprint(certificate),
                    certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())));
        }
        return Collections.unmodifiableList(chain);
    }

    private static int publicKeyBits(X509Certificate certificate) {
        if (certificate.getPublicKey() instanceof RSAPublicKey rsa) {
            return rsa.getModulus().bitLength();
        }
        if (certificate.getPublicKey() instanceof ECPublicKey ec) {
            return ec.getParams().getCurve().getField().getFieldSize();
        }
        return 0;
    }

    private static List<String> subjectAlternativeNames(X509Certificate certificate) {
        try {
            Collection<List<?>> entries = certificate.getSubjectAlternativeNames();
            if (entries == null) {
                return List.of();
            }
            List<String> names = new ArrayList<>();
            for (List<?> entry : entries) {
                if (entry.size() >= 2 && entry.get(1) != null) {
                    names.add(String.valueOf(entry.get(1)));
                }
            }
            return Collections.unmodifiableList(names);
        } catch (Exception unparsable) {
            return List.of();
        }
    }

    private static String fingerprint(X509Certificate certificate) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
            StringBuilder text = new StringBuilder(digest.length * 3);
            for (int i = 0; i < digest.length; i++) {
                if (i > 0) {
                    text.append(':');
                }
                text.append(String.format("%02X", digest[i]));
            }
            return text.toString();
        } catch (Exception unavailable) {
            return "";
        }
    }

    /** 叶子证书的 CN / SAN 是否覆盖目标主机，支持一级通配符。 */
    private static boolean hostnameMatches(String host, List<CertificateInfo> chain) {
        if (chain.isEmpty()) {
            return false;
        }
        CertificateInfo leaf = chain.get(0);
        String target = host.toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>(leaf.subjectAlternativeNames());
        for (String part : leaf.subject().split(",")) {
            String segment = part.trim();
            if (segment.regionMatches(true, 0, "CN=", 0, 3)) {
                candidates.add(segment.substring(3));
            }
        }
        for (String candidate : candidates) {
            String name = candidate.toLowerCase(Locale.ROOT).trim();
            if (name.equals(target)) {
                return true;
            }
            if (name.startsWith("*.")) {
                String suffix = name.substring(1);
                int dot = target.indexOf('.');
                if (dot > 0 && target.substring(dot).equals(suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 只用于「验证失败了，但还是要把证书链取回来看」的第二次握手。
     * 结果一律标记为不可信，绝不用于传输业务数据。
     */
    private static SSLContext trustAllContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, new java.security.SecureRandom());
        return context;
    }

    // ==========================================
    // 通用
    // ==========================================

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    private static long drain(InputStream stream) {
        long total = 0L;
        byte[] buffer = new byte[8192];
        try (InputStream body = stream) {
            int read;
            while ((read = body.read(buffer)) > 0) {
                total += read;
            }
        } catch (Exception truncated) {
            return total;
        }
        return total;
    }

    private static HttpResult errorResult(String url, String error) {
        return new HttpResult(url, 0, null, -1L, -1L, -1L, -1L, -1L, 0L, -1L, "",
                List.of(), Map.of(), error);
    }

    private static void closeQuietly(DirContext context) {
        try {
            context.close();
        } catch (NamingException ignored) {
            // 上下文关闭失败不影响已经取到的记录。
        }
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

    private static String describe(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && (cause.getMessage() == null || cause.getMessage().isEmpty())) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isEmpty()
                ? cause.getClass().getSimpleName()
                : cause.getClass().getSimpleName() + ": " + message;
    }

    /** 常用公共 DNS，方便在「系统解析器」与「权威视角」之间快速切换。 */
    public static List<String> commonResolvers() {
        return Arrays.asList("", "223.5.5.5", "119.29.29.29", "114.114.114.114",
                "8.8.8.8", "1.1.1.1");
    }
}
