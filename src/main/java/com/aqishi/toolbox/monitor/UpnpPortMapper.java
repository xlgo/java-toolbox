package com.aqishi.toolbox.monitor;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Minimal UPnP IGD port mapper used by the direct desktop channel.
 *
 * <p>The mapping is only advertised when the IGD external address agrees with
 * the address observed through STUN. This prevents a mapping on the first
 * router of a double-NAT/CGNAT path from being presented as a reachable public
 * endpoint.</p>
 */
final class UpnpPortMapper {

    private static final String SSDP_HOST = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int DISCOVERY_TIMEOUT_MS = 2300;
    private static final int HTTP_TIMEOUT_MS = 1800;
    private static final long FAILURE_CACHE_MS = 30000;
    private static final Object DISCOVERY_LOCK = new Object();

    private static volatile Gateway cachedGateway;
    private static volatile long lastDiscoveryFailureAt;

    private UpnpPortMapper() {
    }

    static PortMapping map(String protocol,
                           int internalPort,
                           Collection<InetSocketAddress> stunAddresses,
                           Consumer<String> log) {
        String normalizedProtocol = protocol == null ? "" : protocol.toUpperCase(Locale.ROOT);
        if (!"UDP".equals(normalizedProtocol) && !"TCP".equals(normalizedProtocol)) {
            throw new IllegalArgumentException("protocol must be UDP or TCP");
        }
        if (internalPort < 1 || internalPort > 65535) {
            throw new IllegalArgumentException("invalid internal port: " + internalPort);
        }

        Gateway gateway = discover(log);
        if (gateway == null) return null;

        try {
            String externalIp = gateway.getExternalIpAddress();
            Set<String> stunIps = new LinkedHashSet<>();
            if (stunAddresses != null) {
                for (InetSocketAddress address : stunAddresses) {
                    if (address != null && address.getAddress() instanceof Inet4Address) {
                        stunIps.add(address.getAddress().getHostAddress());
                    }
                }
            }

            boolean verified = isPublicIpv4(externalIp)
                    && (stunIps.isEmpty() || stunIps.contains(externalIp));
            if (!verified) {
                if (!stunIps.isEmpty()) {
                    log.accept("UPnP 网关外网地址 " + externalIp + " 与 STUN 公网地址 "
                            + stunIps + " 不一致，已确认存在双重 NAT/运营商 CGNAT；"
                            + normalizedProtocol + " 映射无法到达真实公网。");
                } else {
                    log.accept("UPnP 网关返回的外网地址 " + externalIp
                            + " 不是可路由公网 IPv4，不发布 " + normalizedProtocol + " 映射候选。");
                }
                return null;
            }

            InetAddress internalAddress = gateway.getInternalAddress();
            List<Integer> externalPorts = candidateExternalPorts(internalPort);
            SoapException lastFailure = null;
            for (int externalPort : externalPorts) {
                try {
                    gateway.addPortMapping(
                            normalizedProtocol, externalPort, internalPort,
                            internalAddress.getHostAddress(), 3600);
                    PortMapping mapping = new PortMapping(
                            gateway, normalizedProtocol, externalIp, externalPort,
                            internalAddress.getHostAddress(), internalPort);
                    log.accept("UPnP 已建立并校验 " + normalizedProtocol + " 公网端口映射: "
                            + externalIp + ":" + externalPort + " -> "
                            + mapping.internalIp + ":" + internalPort);
                    return mapping;
                } catch (SoapException e) {
                    lastFailure = e;
                    if (e.errorCode == 725) {
                        try {
                            gateway.addPortMapping(
                                    normalizedProtocol, externalPort, internalPort,
                                    internalAddress.getHostAddress(), 0);
                            PortMapping mapping = new PortMapping(
                                    gateway, normalizedProtocol, externalIp, externalPort,
                                    internalAddress.getHostAddress(), internalPort);
                            log.accept("UPnP 已建立并校验 " + normalizedProtocol + " 公网端口映射: "
                                    + externalIp + ":" + externalPort + " -> "
                                    + mapping.internalIp + ":" + internalPort);
                            return mapping;
                        } catch (SoapException retryFailure) {
                            lastFailure = retryFailure;
                        }
                    }
                    // 718 means that the requested public port is already owned.
                    if (e.errorCode != 718 && e.errorCode != 725) break;
                }
            }

            String reason = lastFailure == null ? "unknown error" : lastFailure.getMessage();
            log.accept("UPnP " + normalizedProtocol + " 端口映射失败: " + reason
                    + "。仍会检查局域网、IPv6 和普通 STUN 候选。");
        } catch (Exception e) {
            log.accept("UPnP " + normalizedProtocol + " 端口映射检查失败: "
                    + conciseError(e) + "。仍会检查其他直连候选。");
        }
        return null;
    }

    private static Gateway discover(Consumer<String> log) {
        Gateway existing = cachedGateway;
        if (existing != null) return existing;
        long now = System.currentTimeMillis();
        if (now - lastDiscoveryFailureAt < FAILURE_CACHE_MS) return null;

        synchronized (DISCOVERY_LOCK) {
            if (cachedGateway != null) return cachedGateway;
            now = System.currentTimeMillis();
            if (now - lastDiscoveryFailureAt < FAILURE_CACHE_MS) return null;

            try {
                for (URL location : discoverDescriptionUrls()) {
                    try {
                        Gateway gateway = Gateway.fromDescription(location);
                        if (gateway != null) {
                            cachedGateway = gateway;
                            log.accept("发现 UPnP Internet Gateway: " + gateway.controlUrl);
                            return gateway;
                        }
                    } catch (Exception ignored) {
                        // Some SSDP responders are media devices rather than an IGD.
                    }
                }
            } catch (Exception ignored) {
                // The caller receives one concise diagnostic below.
            }

            lastDiscoveryFailureAt = System.currentTimeMillis();
            log.accept("未发现可用的 UPnP Internet Gateway，无法自动开放公网端口。"
                    + "如果路由器支持 UPnP，请确认该功能已启用。");
            return null;
        }
    }

    private static List<URL> discoverDescriptionUrls() throws IOException {
        String[] searchTargets = {
                "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
                "urn:schemas-upnp-org:service:WANIPConnection:2",
                "urn:schemas-upnp-org:service:WANIPConnection:1",
                "upnp:rootdevice"
        };
        Set<URL> locations = new LinkedHashSet<>();
        InetAddress multicast = InetAddress.getByName(SSDP_HOST);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(350);
            for (String target : searchTargets) {
                String request = "M-SEARCH * HTTP/1.1\r\n"
                        + "HOST: " + SSDP_HOST + ":" + SSDP_PORT + "\r\n"
                        + "MAN: \"ssdp:discover\"\r\n"
                        + "MX: 2\r\n"
                        + "ST: " + target + "\r\n"
                        + "\r\n";
                byte[] bytes = request.getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(
                        bytes, bytes.length, new InetSocketAddress(multicast, SSDP_PORT)));
            }

            long deadline = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS;
            byte[] buffer = new byte[8192];
            while (System.currentTimeMillis() < deadline) {
                try {
                    DatagramPacket response = new DatagramPacket(buffer, buffer.length);
                    socket.receive(response);
                    String text = new String(
                            response.getData(), response.getOffset(), response.getLength(),
                            StandardCharsets.ISO_8859_1);
                    String location = headerValue(text, "location");
                    if (location != null) {
                        locations.add(new URL(location.trim()));
                    }
                } catch (java.net.SocketTimeoutException ignored) {
                    // Keep receiving until the overall discovery deadline.
                }
            }
        }
        return new ArrayList<>(locations);
    }

    private static String headerValue(String response, String name) {
        String[] lines = response.split("\\r?\\n");
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            if (name.equalsIgnoreCase(line.substring(0, colon).trim())) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private static List<Integer> candidateExternalPorts(int preferred) {
        List<Integer> ports = new ArrayList<>();
        ports.add(preferred);
        for (int distance = 1; distance <= 8; distance++) {
            if (preferred + distance <= 65535) ports.add(preferred + distance);
            if (preferred - distance >= 1024) ports.add(preferred - distance);
        }
        return ports;
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

    private static String conciseError(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName()
                + (message == null || message.trim().isEmpty() ? "" : ": " + message);
    }

    static final class PortMapping {
        private final Gateway gateway;
        private final String protocol;
        private final String externalIp;
        private final int externalPort;
        private final String internalIp;
        private final int internalPort;
        private final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean(false);

        private PortMapping(Gateway gateway,
                            String protocol,
                            String externalIp,
                            int externalPort,
                            String internalIp,
                            int internalPort) {
            this.gateway = gateway;
            this.protocol = protocol;
            this.externalIp = externalIp;
            this.externalPort = externalPort;
            this.internalIp = internalIp;
            this.internalPort = internalPort;
        }

        InetSocketAddress getExternalAddress() {
            return new InetSocketAddress(externalIp, externalPort);
        }

        void close(Consumer<String> log) {
            if (!closed.compareAndSet(false, true)) return;
            try {
                gateway.deletePortMapping(protocol, externalPort);
            } catch (Exception e) {
                if (log != null) {
                    log.accept("清理 UPnP " + protocol + " 映射失败: " + conciseError(e));
                }
            }
        }
    }

    private static final class Gateway {
        private final URL controlUrl;
        private final String serviceType;
        private final InetAddress internalAddress;

        private Gateway(URL controlUrl, String serviceType, InetAddress internalAddress) {
            this.controlUrl = controlUrl;
            this.serviceType = serviceType;
            this.internalAddress = internalAddress;
        }

        static Gateway fromDescription(URL descriptionUrl) throws Exception {
            byte[] xml = httpGet(descriptionUrl);
            Document document = parseXml(xml);
            NodeList services = document.getElementsByTagNameNS("*", "service");
            if (services.getLength() == 0) {
                services = document.getElementsByTagName("service");
            }

            String selectedType = null;
            String selectedControlUrl = null;
            for (int i = 0; i < services.getLength(); i++) {
                Element service = (Element) services.item(i);
                String serviceType = childText(service, "serviceType");
                if (serviceType == null) continue;
                if (serviceType.contains(":WANIPConnection:")
                        || serviceType.contains(":WANPPPConnection:")) {
                    selectedType = serviceType;
                    selectedControlUrl = childText(service, "controlURL");
                    if (serviceType.contains(":WANIPConnection:")) break;
                }
            }
            if (selectedType == null || selectedControlUrl == null) return null;

            URL controlUrl = new URL(descriptionUrl, selectedControlUrl);
            int port = controlUrl.getPort() > 0 ? controlUrl.getPort() : controlUrl.getDefaultPort();
            InetAddress internalAddress;
            try (DatagramSocket routeProbe = new DatagramSocket()) {
                routeProbe.connect(InetAddress.getByName(controlUrl.getHost()), port);
                internalAddress = routeProbe.getLocalAddress();
            }
            if (!(internalAddress instanceof Inet4Address)
                    || internalAddress.isAnyLocalAddress()) {
                return null;
            }
            return new Gateway(controlUrl, selectedType, internalAddress);
        }

        InetAddress getInternalAddress() {
            return internalAddress;
        }

        synchronized String getExternalIpAddress() throws Exception {
            byte[] response = invoke("GetExternalIPAddress", Collections.<String[]>emptyList());
            String address = elementText(parseXml(response), "NewExternalIPAddress");
            if (address == null || address.trim().isEmpty()) {
                throw new IOException("GetExternalIPAddress returned no address");
            }
            return address.trim();
        }

        synchronized void addPortMapping(String protocol,
                                         int externalPort,
                                         int internalPort,
                                         String internalClient,
                                         int leaseSeconds) throws Exception {
            List<String[]> arguments = new ArrayList<>();
            arguments.add(new String[]{"NewRemoteHost", ""});
            arguments.add(new String[]{"NewExternalPort", String.valueOf(externalPort)});
            arguments.add(new String[]{"NewProtocol", protocol});
            arguments.add(new String[]{"NewInternalPort", String.valueOf(internalPort)});
            arguments.add(new String[]{"NewInternalClient", internalClient});
            arguments.add(new String[]{"NewEnabled", "1"});
            arguments.add(new String[]{"NewPortMappingDescription", "JavaToolbox-RemoteDesktop"});
            arguments.add(new String[]{"NewLeaseDuration", String.valueOf(leaseSeconds)});
            invoke("AddPortMapping", arguments);
        }

        synchronized void deletePortMapping(String protocol, int externalPort) throws Exception {
            List<String[]> arguments = new ArrayList<>();
            arguments.add(new String[]{"NewRemoteHost", ""});
            arguments.add(new String[]{"NewExternalPort", String.valueOf(externalPort)});
            arguments.add(new String[]{"NewProtocol", protocol});
            invoke("DeletePortMapping", arguments);
        }

        private byte[] invoke(String action, List<String[]> arguments) throws Exception {
            StringBuilder body = new StringBuilder();
            body.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
                    .append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
                    .append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">")
                    .append("<s:Body><u:").append(action)
                    .append(" xmlns:u=\"").append(xmlEscape(serviceType)).append("\">");
            for (String[] argument : arguments) {
                body.append('<').append(argument[0]).append('>')
                        .append(xmlEscape(argument[1]))
                        .append("</").append(argument[0]).append('>');
            }
            body.append("</u:").append(action).append("></s:Body></s:Envelope>");

            HttpURLConnection connection = (HttpURLConnection) controlUrl.openConnection();
            connection.setConnectTimeout(HTTP_TIMEOUT_MS);
            connection.setReadTimeout(HTTP_TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("SOAPAction", "\"" + serviceType + "#" + action + "\"");
            byte[] request = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }

            int status = connection.getResponseCode();
            InputStream responseStream = status >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            byte[] response = responseStream == null ? new byte[0] : readAll(responseStream);
            connection.disconnect();
            if (status >= 400) {
                int errorCode = parseInteger(elementText(parseXmlLenient(response), "errorCode"), -1);
                String description = elementText(parseXmlLenient(response), "errorDescription");
                throw new SoapException(errorCode,
                        action + " failed (HTTP " + status + ", UPnP " + errorCode + ")"
                                + (description == null ? "" : ": " + description));
            }
            return response;
        }
    }

    private static byte[] httpGet(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(HTTP_TIMEOUT_MS);
        connection.setReadTimeout(HTTP_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        int status = connection.getResponseCode();
        if (status >= 400) {
            connection.disconnect();
            throw new IOException("HTTP " + status + " loading " + url);
        }
        try (InputStream input = connection.getInputStream()) {
            return readAll(input);
        } finally {
            connection.disconnect();
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setExpandEntityReferences(false);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySetFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        trySetFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }

    private static Document parseXmlLenient(byte[] xml) {
        try {
            return parseXml(xml);
        } catch (Exception e) {
            return null;
        }
    }

    private static void trySetFeature(DocumentBuilderFactory factory, String feature, boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (Exception ignored) {
        }
    }

    private static String childText(Element parent, String localName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            String name = child.getLocalName() == null ? child.getNodeName() : child.getLocalName();
            if (localName.equals(name)) return child.getTextContent().trim();
        }
        return null;
    }

    private static String elementText(Document document, String localName) {
        if (document == null) return null;
        NodeList nodes = document.getElementsByTagNameNS("*", localName);
        if (nodes.getLength() == 0) nodes = document.getElementsByTagName(localName);
        return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent().trim();
    }

    private static int parseInteger(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String xmlEscape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static final class SoapException extends IOException {
        private final int errorCode;

        private SoapException(int errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }
    }
}
