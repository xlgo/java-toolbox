package com.aqishi.toolbox.monitor;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NAT-PMP (RFC 6886) fallback for routers that do not expose UPnP IGD.
 */
final class NatPmpPortMapper {

    private static final int NAT_PMP_PORT = 5351;
    private static final long FAILURE_CACHE_MS = 30000;
    private static final int[] RECEIVE_TIMEOUTS_MS = {300, 600, 1100};
    private static volatile long lastFailureAt;

    private NatPmpPortMapper() {
    }

    static PortMapping map(String protocol,
                           int internalPort,
                           Collection<InetSocketAddress> stunAddresses,
                           Consumer<String> log) {
        long now = System.currentTimeMillis();
        if (now - lastFailureAt < FAILURE_CACHE_MS) return null;

        String normalized = protocol == null ? "" : protocol.toUpperCase(Locale.ROOT);
        int opcode = "UDP".equals(normalized) ? 1 : ("TCP".equals(normalized) ? 2 : -1);
        if (opcode < 0) throw new IllegalArgumentException("protocol must be UDP or TCP");

        try {
            InetAddress gateway = findDefaultGateway();
            if (!(gateway instanceof Inet4Address)) {
                lastFailureAt = System.currentTimeMillis();
                log.accept("未找到 IPv4 默认网关，无法尝试 NAT-PMP 端口映射。");
                return null;
            }

            InetSocketAddress gatewayEndpoint =
                    new InetSocketAddress(gateway, NAT_PMP_PORT);
            byte[] publicResponse = exchange(gatewayEndpoint, new byte[]{0, 0}, 128);
            int publicResult = unsignedShort(publicResponse, 2);
            if (publicResult != 0 || publicResponse.length < 12) {
                throw new IllegalStateException("public-address result=" + publicResult);
            }
            String gatewayPublicIp = InetAddress.getByAddress(new byte[]{
                    publicResponse[8], publicResponse[9], publicResponse[10], publicResponse[11]
            }).getHostAddress();

            Set<String> stunIps = new LinkedHashSet<>();
            int suggestedExternalPort = internalPort;
            if (stunAddresses != null) {
                for (InetSocketAddress address : stunAddresses) {
                    if (address != null && address.getAddress() instanceof Inet4Address) {
                        stunIps.add(address.getAddress().getHostAddress());
                        if (suggestedExternalPort == internalPort) {
                            suggestedExternalPort = address.getPort();
                        }
                    }
                }
            }
            if (!isPublicIpv4(gatewayPublicIp)
                    || (!stunIps.isEmpty() && !stunIps.contains(gatewayPublicIp))) {
                lastFailureAt = System.currentTimeMillis();
                log.accept("NAT-PMP 网关外网地址 " + gatewayPublicIp + " 与 STUN 公网地址 "
                        + stunIps + " 不一致，属于双重 NAT/CGNAT，映射不能直达公网。");
                return null;
            }

            ByteBuffer request = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
            request.put((byte) 0);
            request.put((byte) opcode);
            request.putShort((short) 0);
            request.putShort((short) internalPort);
            request.putShort((short) suggestedExternalPort);
            request.putInt(3600);

            byte[] mapResponse = exchange(
                    gatewayEndpoint, request.array(), 128 + opcode);
            int result = unsignedShort(mapResponse, 2);
            if (result != 0 || mapResponse.length < 16) {
                throw new IllegalStateException("mapping result=" + result);
            }
            int returnedInternalPort = unsignedShort(mapResponse, 8);
            int externalPort = unsignedShort(mapResponse, 10);
            long lifetime = unsignedInt(mapResponse, 12);
            if (returnedInternalPort != internalPort || externalPort < 1 || lifetime == 0) {
                throw new IllegalStateException("invalid mapping response");
            }

            PortMapping mapping = new PortMapping(
                    gatewayEndpoint, normalized, opcode, gatewayPublicIp,
                    externalPort, internalPort);
            log.accept("NAT-PMP 已建立并校验 " + normalized + " 公网端口映射: "
                    + gatewayPublicIp + ":" + externalPort + " -> local:" + internalPort
                    + " (lifetime=" + lifetime + "s)");
            return mapping;
        } catch (Exception e) {
            lastFailureAt = System.currentTimeMillis();
            log.accept("NAT-PMP " + normalized + " 端口映射不可用: "
                    + conciseError(e) + "。");
            return null;
        }
    }

    private static byte[] exchange(InetSocketAddress gateway,
                                   byte[] request,
                                   int expectedOpcode) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] responseBuffer = new byte[64];
            for (int timeout : RECEIVE_TIMEOUTS_MS) {
                socket.send(new DatagramPacket(request, request.length, gateway));
                socket.setSoTimeout(timeout);
                try {
                    DatagramPacket response =
                            new DatagramPacket(responseBuffer, responseBuffer.length);
                    socket.receive(response);
                    if (!gateway.equals(response.getSocketAddress())
                            || response.getLength() < 8
                            || (responseBuffer[0] & 0xff) != 0
                            || (responseBuffer[1] & 0xff) != expectedOpcode) {
                        continue;
                    }
                    byte[] result = new byte[response.getLength()];
                    System.arraycopy(
                            response.getData(), response.getOffset(), result, 0, response.getLength());
                    return result;
                } catch (java.net.SocketTimeoutException ignored) {
                }
            }
        }
        throw new java.net.SocketTimeoutException("gateway " + gateway + " did not respond");
    }

    private static InetAddress findDefaultGateway() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                String output = runCommand("route", "print", "-4");
                Pattern route = Pattern.compile(
                        "(?m)^\\s*0\\.0\\.0\\.0\\s+0\\.0\\.0\\.0\\s+"
                                + "(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s+"
                                + "\\d+\\.\\d+\\.\\d+\\.\\d+\\s+\\d+\\s*$");
                Matcher matcher = route.matcher(output);
                if (matcher.find()) return InetAddress.getByName(matcher.group(1));
            } else if (os.contains("mac")) {
                String output = runCommand("route", "-n", "get", "default");
                Matcher matcher = Pattern.compile(
                        "(?m)^\\s*gateway:\\s*(\\d+\\.\\d+\\.\\d+\\.\\d+)\\s*$")
                        .matcher(output);
                if (matcher.find()) return InetAddress.getByName(matcher.group(1));
            } else {
                String output = runCommand("ip", "-4", "route", "show", "default");
                Matcher matcher = Pattern.compile(
                        "(?m)^default\\s+via\\s+(\\d+\\.\\d+\\.\\d+\\.\\d+)\\b")
                        .matcher(output);
                if (matcher.find()) return InetAddress.getByName(matcher.group(1));
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String runCommand(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append('\n');
            }
        }
        if (!process.waitFor(1500, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("gateway command timed out");
        }
        return output.toString();
    }

    private static boolean isPublicIpv4(String ip) {
        try {
            InetAddress address = InetAddress.getByName(ip);
            if (!(address instanceof Inet4Address)
                    || address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return false;
            }
            byte[] bytes = address.getAddress();
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return !(first == 100 && second >= 64 && second <= 127);
        } catch (Exception e) {
            return false;
        }
    }

    private static int unsignedShort(byte[] data, int offset) {
        if (data.length < offset + 2) return -1;
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static long unsignedInt(byte[] data, int offset) {
        if (data.length < offset + 4) return -1;
        return ((long) (data[offset] & 0xff) << 24)
                | ((long) (data[offset + 1] & 0xff) << 16)
                | ((long) (data[offset + 2] & 0xff) << 8)
                | (data[offset + 3] & 0xff);
    }

    private static String conciseError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    static final class PortMapping {
        private final InetSocketAddress gateway;
        private final String protocol;
        private final int opcode;
        private final String externalIp;
        private final int externalPort;
        private final int internalPort;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private PortMapping(InetSocketAddress gateway,
                            String protocol,
                            int opcode,
                            String externalIp,
                            int externalPort,
                            int internalPort) {
            this.gateway = gateway;
            this.protocol = protocol;
            this.opcode = opcode;
            this.externalIp = externalIp;
            this.externalPort = externalPort;
            this.internalPort = internalPort;
        }

        InetSocketAddress getExternalAddress() {
            return new InetSocketAddress(externalIp, externalPort);
        }

        void close(Consumer<String> log) {
            if (!closed.compareAndSet(false, true)) return;
            try {
                ByteBuffer request = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
                request.put((byte) 0);
                request.put((byte) opcode);
                request.putShort((short) 0);
                request.putShort((short) internalPort);
                request.putShort((short) externalPort);
                request.putInt(0);
                exchange(gateway, request.array(), 128 + opcode);
            } catch (Exception e) {
                if (log != null) {
                    log.accept("清理 NAT-PMP " + protocol + " 映射失败: " + conciseError(e));
                }
            }
        }
    }
}
