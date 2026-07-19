package com.aqishi.toolbox.monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ice4j.Transport;
import org.ice4j.TransportAddress;
import org.ice4j.ice.Agent;
import org.ice4j.ice.CandidatePair;
import org.ice4j.ice.CandidatePairState;
import org.ice4j.ice.CandidateType;
import org.ice4j.ice.CheckList;
import org.ice4j.ice.Component;
import org.ice4j.ice.IceMediaStream;
import org.ice4j.ice.IceProcessingState;
import org.ice4j.ice.KeepAliveStrategy;
import org.ice4j.ice.LocalCandidate;
import org.ice4j.ice.NominationStrategy;
import org.ice4j.ice.RemoteCandidate;
import org.ice4j.ice.harvest.StunCandidateHarvester;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * 完整 ICE UDP 直连协商器。
 *
 * <p>与原先只模仿 ICE 发探测包的实现不同，这里把候选对构造、角色冲突处理、
 * triggered check、peer-reflexive candidate、重传和提名全部交给 ice4j。应用只
 * 注册普通 STUN harvester，不注册 TURN harvester，也不会产生 relay candidate。</p>
 */
public final class Ice4jDirectConnector {

    public static final String PROTOCOL = "java-toolbox-ice4j/6";
    private static final String DESCRIPTION_PREFIX = PROTOCOL + ":";
    private static final String STREAM_NAME = "desktop";
    private static final int CHECK_TIMEOUT_SECONDS = 25;
    private static final int DIAGNOSTIC_INTERVAL_SECONDS = 5;
    private static final int MAX_REMOTE_CANDIDATES = 128;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final List<InetSocketAddress> DEFAULT_STUN_SERVERS;

    static {
        List<InetSocketAddress> servers = new ArrayList<>();
        servers.add(InetSocketAddress.createUnresolved("stun.cloudflare.com", 3478));
        servers.add(InetSocketAddress.createUnresolved("global.stun.twilio.com", 3478));
        servers.add(InetSocketAddress.createUnresolved("stun.l.google.com", 19302));
        servers.add(InetSocketAddress.createUnresolved("stun1.l.google.com", 19302));
        DEFAULT_STUN_SERVERS = Collections.unmodifiableList(servers);
    }

    private final List<InetSocketAddress> stunServers;

    private Agent agent;
    private IceMediaStream stream;
    private Component component;
    private ScheduledExecutorService scheduler;
    private Consumer<DesktopChannel> successCallback;
    private Runnable failureCallback;
    private Consumer<String> logCallback;
    private ManagedIceChannel activeChannel;
    private List<InetSocketAddress> publicAddresses = Collections.emptyList();
    private long generation;
    private boolean checksStarted;
    private boolean finished;

    public Ice4jDirectConnector() {
        this(DEFAULT_STUN_SERVERS);
    }

    Ice4jDirectConnector(List<InetSocketAddress> stunServers) {
        this.stunServers = stunServers == null
                ? Collections.<InetSocketAddress>emptyList()
                : Collections.unmodifiableList(new ArrayList<>(stunServers));
    }

    /**
     * 创建本地 ICE agent 并同步收集 host/srflx 候选。
     *
     * @return 可直接放进 offer/answer 的自包含 ICE 描述符
     */
    public String prepare(boolean controlling,
                          Consumer<DesktopChannel> onSuccess,
                          Runnable onFailure,
                          Consumer<String> log) throws Exception {
        stop();
        requireSupportedJava();

        synchronized (this) {
            generation++;
            finished = false;
            checksStarted = false;
            successCallback = onSuccess;
            failureCallback = onFailure;
            logCallback = log == null ? ignored -> { } : log;

            Agent createdAgent = new Agent();
            createdAgent.setControlling(controlling);
            createdAgent.setTrickling(false);
            createdAgent.setUseDynamicPorts(true);
            createdAgent.setPerformConsentFreshness(true);
            createdAgent.setNominationStrategy(
                    NominationStrategy.NOMINATE_FIRST_HOST_OR_REFLEXIVE_VALID);
            // Detailed, stable diagnostics are emitted through logCallback.
            // Avoid flooding the process console with every retransmission.
            createdAgent.setLoggingLevel(Level.WARNING);
            // Publish ownership immediately so failSession()/stop() can free
            // the agent if candidate harvesting throws halfway through.
            agent = createdAgent;

            // Only STUN is configured. There is intentionally no TURN harvester.
            for (InetSocketAddress server : stunServers) {
                if (server == null || server.getPort() < 1) continue;
                createdAgent.addCandidateHarvester(new StunCandidateHarvester(
                        new TransportAddress(server.getHostString(), server.getPort(), Transport.UDP)));
            }

            IceMediaStream createdStream = createdAgent.createMediaStream(STREAM_NAME);
            stream = createdStream;
            Component createdComponent = createdAgent.createComponent(
                    createdStream, 0, 0, 0, KeepAliveStrategy.SELECTED_ONLY, true);

            component = createdComponent;
            publicAddresses = collectPublicAddresses(createdComponent);

            Description description = createDescription(createdAgent, createdComponent);
            logCallback.accept("ICE 本地候选收集完成: role="
                    + (controlling ? "controlling" : "controlled")
                    + ", java=" + System.getProperty("java.home")
                    + ", STUN=" + stunServers.size()
                    + ", " + summarizeCandidates(description.candidates)
                    + ", TURN=disabled, relayCandidates=0");
            for (CandidateInfo candidate : description.candidates) {
                logCallback.accept("ICE 本地候选: " + candidate);
            }
            return encode(description);
        }
    }

    /**
     * 安装对端凭据和全部候选。UDP 候选随 offer/answer 一次发送，避免 trickle
     * candidate 与凭据乱序造成检查表提前失败。
     */
    public synchronized void setRemoteDescription(String encodedDescription) throws Exception {
        ensurePrepared();
        if (checksStarted) {
            throw new IllegalStateException("ICE connectivity checks already started");
        }

        Description remote = decode(encodedDescription);
        stream.setRemoteUfrag(remote.ufrag);
        stream.setRemotePassword(remote.password);

        int accepted = 0;
        for (CandidateInfo info : remote.candidates) {
            if (accepted >= MAX_REMOTE_CANDIDATES) break;
            CandidateType type = parseDirectCandidateType(info.type);
            if (type == null) continue;
            if (info.port < 1 || info.port > 65535 || info.ip == null || info.ip.trim().isEmpty()) {
                continue;
            }

            InetAddress address = InetAddress.getByName(stripIpv6Brackets(info.ip.trim()));
            TransportAddress transportAddress =
                    new TransportAddress(address, info.port, Transport.UDP);
            if (component.findRemoteCandidate(transportAddress) != null) continue;

            RemoteCandidate candidate = new RemoteCandidate(
                    transportAddress,
                    component,
                    type,
                    safeFoundation(info.foundation),
                    normalizePriority(info.priority),
                    null,
                    remote.ufrag);
            component.addRemoteCandidate(candidate);
            if (component.getDefaultRemoteCandidate() == null) {
                component.setDefaultRemoteCandidate(candidate);
            }
            accepted++;
            logCallback.accept("ICE 加入远端候选: " + info);
        }

        if (accepted == 0) {
            throw new IllegalArgumentException("remote ICE description has no direct UDP candidates");
        }
        logCallback.accept("ICE 远端描述已安装: candidates=" + accepted
                + ", credentials=present, relayCandidates=0");
    }

    public void startConnectivityChecks() {
        final Agent currentAgent;
        final long currentGeneration;
        synchronized (this) {
            ensurePrepared();
            if (checksStarted || finished) return;
            if (component.getRemoteCandidateCount() == 0
                    || stream.getRemoteUfrag() == null
                    || stream.getRemotePassword() == null) {
                throw new IllegalStateException("remote ICE description is incomplete");
            }

            checksStarted = true;
            currentAgent = agent;
            currentGeneration = generation;
            currentAgent.addStateChangeListener(event -> {
                Object value = event.getNewValue();
                if (value instanceof IceProcessingState) {
                    handleStateChange(currentGeneration, (IceProcessingState) value);
                }
            });

            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "Ice4jDirect-Diagnostics");
                thread.setDaemon(true);
                return thread;
            });
            scheduler.scheduleAtFixedRate(
                    () -> logProgress(currentGeneration),
                    DIAGNOSTIC_INTERVAL_SECONDS,
                    DIAGNOSTIC_INTERVAL_SECONDS,
                    TimeUnit.SECONDS);
            scheduler.schedule(
                    () -> fail(currentGeneration, "ICE connectivity checks timed out"),
                    CHECK_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            logCallback.accept("ICE 开始连接性检查: localCandidates="
                    + component.getLocalCandidateCount()
                    + ", remoteCandidates=" + component.getRemoteCandidateCount()
                    + ", role=" + (currentAgent.isControlling() ? "controlling" : "controlled"));
        }
        currentAgent.startConnectivityEstablishment();
    }

    public synchronized List<InetSocketAddress> getPublicAddresses() {
        return new ArrayList<>(publicAddresses);
    }

    public void failSession(String reason) {
        final long currentGeneration;
        synchronized (this) {
            currentGeneration = generation;
        }
        fail(currentGeneration, reason == null ? "ICE session failed" : reason);
    }

    public void stop() {
        ManagedIceChannel channelToClose;
        Agent agentToFree;
        synchronized (this) {
            generation++;
            finished = true;
            checksStarted = false;
            cancelScheduler();
            channelToClose = activeChannel;
            activeChannel = null;
            agentToFree = agent;
            agent = null;
            stream = null;
            component = null;
            successCallback = null;
            failureCallback = null;
            logCallback = null;
        }

        if (channelToClose != null) channelToClose.close();
        if (agentToFree != null) agentToFree.free();
    }

    public static boolean isDescription(String value) {
        return value != null && value.startsWith(DESCRIPTION_PREFIX);
    }

    private void handleStateChange(long expectedGeneration, IceProcessingState state) {
        Consumer<String> logger;
        synchronized (this) {
            if (expectedGeneration != generation || finished) return;
            logger = logCallback;
        }
        if (logger != null) {
            logger.accept("ICE 状态: " + state + "；" + buildChecklistSummary());
        }

        if (state == IceProcessingState.COMPLETED) {
            complete(expectedGeneration);
        } else if (state == IceProcessingState.FAILED
                || state == IceProcessingState.TERMINATED) {
            // Do not enter TCP immediately on an early terminal event. Both
            // peers use the same 25-second barrier so their 15-second TCP
            // listener windows overlap even when one ICE checklist fails
            // several seconds before the other.
            if (logger != null) {
                logger.accept("ICE 检查已提前结束，等待统一的 "
                        + CHECK_TIMEOUT_SECONDS
                        + " 秒切换点后双方同步进入 TCP 直连。");
            }
        }
    }

    private void complete(long expectedGeneration) {
        Consumer<DesktopChannel> callback;
        Consumer<String> logger;
        ManagedIceChannel channel;
        synchronized (this) {
            if (expectedGeneration != generation || finished) return;
            CandidatePair selectedPair = component == null ? null : component.getSelectedPair();
            DatagramSocket socket = component == null ? null : component.getSocket();
            if (selectedPair == null || socket == null) {
                fail(expectedGeneration, "ICE completed without a selected UDP candidate pair");
                return;
            }

            TransportAddress remote = selectedPair.getRemoteCandidate().getTransportAddress();
            InetSocketAddress remoteAddress =
                    new InetSocketAddress(remote.getAddress(), remote.getPort());
            UdpChannelImpl delegate = new UdpChannelImpl(socket, remoteAddress);
            channel = new ManagedIceChannel(this, expectedGeneration, delegate);

            finished = true;
            cancelScheduler();
            activeChannel = channel;
            callback = successCallback;
            logger = logCallback;
            successCallback = null;
            failureCallback = null;
        }

        if (logger != null) {
            CandidatePair pair;
            synchronized (this) {
                pair = component == null ? null : component.getSelectedPair();
            }
            logger.accept("ICE UDP 直连成功: selectedPair="
                    + (pair == null ? "-" : describePair(pair))
                    + ", TURN=disabled, dataRelay=false");
        }
        if (callback != null) callback.accept(channel);
        else channel.close();
    }

    private void fail(long expectedGeneration, String reason) {
        Runnable callback;
        Consumer<String> logger;
        Agent agentToFree;
        String diagnostic;
        List<String> pairDiagnostics;
        synchronized (this) {
            if (expectedGeneration != generation || finished) return;
            finished = true;
            diagnostic = buildChecklistSummary();
            pairDiagnostics = buildPairDiagnostics();
            cancelScheduler();
            callback = failureCallback;
            logger = logCallback;
            agentToFree = agent;
            agent = null;
            stream = null;
            component = null;
            publicAddresses = Collections.emptyList();
            successCallback = null;
            failureCallback = null;
        }

        if (logger != null) {
            logger.accept("ICE UDP 直连失败: " + reason + "；" + diagnostic
                    + "；TURN=disabled, 未尝试数据中转");
            if (pairDiagnostics.isEmpty()) {
                logger.accept("ICE 失败判定: 未生成候选对，请检查双方描述中的地址族、"
                        + "网卡地址和 STUN srflx 候选。");
            } else {
                for (String pairDiagnostic : pairDiagnostics) {
                    logger.accept("ICE 候选对结果: " + pairDiagnostic);
                }
            }
        }
        if (agentToFree != null) agentToFree.free();
        if (callback != null) callback.run();
    }

    private void logProgress(long expectedGeneration) {
        Consumer<String> logger;
        String summary;
        synchronized (this) {
            if (expectedGeneration != generation || finished) return;
            logger = logCallback;
            summary = buildChecklistSummary();
        }
        if (logger != null) logger.accept("ICE 检查进度: " + summary);
    }

    private synchronized String buildChecklistSummary() {
        Agent currentAgent = agent;
        Component currentComponent = component;
        IceMediaStream currentStream = stream;
        if (currentAgent == null || currentComponent == null || currentStream == null) {
            return "state=closed";
        }

        int waiting = 0;
        int inProgress = 0;
        int succeeded = 0;
        int failed = 0;
        int frozen = 0;
        CheckList checkList = currentStream.getCheckList();
        if (checkList != null) {
            for (CandidatePair pair : checkList) {
                CandidatePairState state = pair.getState();
                if (state == CandidatePairState.WAITING) waiting++;
                else if (state == CandidatePairState.IN_PROGRESS) inProgress++;
                else if (state == CandidatePairState.SUCCEEDED) succeeded++;
                else if (state == CandidatePairState.FAILED) failed++;
                else if (state == CandidatePairState.FROZEN) frozen++;
            }
        }
        return "state=" + currentAgent.getState()
                + ", pairs=" + (checkList == null ? 0 : checkList.size())
                + "{waiting=" + waiting
                + ", inProgress=" + inProgress
                + ", succeeded=" + succeeded
                + ", failed=" + failed
                + ", frozen=" + frozen + "}"
                + ", localCandidates=" + currentComponent.getLocalCandidateCount()
                + ", remoteCandidates=" + currentComponent.getRemoteCandidateCount();
    }

    private synchronized List<String> buildPairDiagnostics() {
        if (stream == null || stream.getCheckList() == null) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        int limit = 24;
        for (CandidatePair pair : stream.getCheckList()) {
            if (result.size() >= limit) break;
            result.add("state=" + pair.getState()
                    + ", valid=" + pair.isValid()
                    + ", nominated=" + pair.isNominated()
                    + ", " + describePair(pair));
        }
        if (stream.getCheckList().size() > limit) {
            result.add("其余 " + (stream.getCheckList().size() - limit)
                    + " 个低优先级候选对已省略");
        }
        return result;
    }

    private synchronized void onChannelClosed(long expectedGeneration,
                                              ManagedIceChannel channel) {
        if (expectedGeneration != generation || activeChannel != channel) return;
        activeChannel = null;
        Agent agentToFree = agent;
        agent = null;
        stream = null;
        component = null;
        publicAddresses = Collections.emptyList();
        if (agentToFree != null) agentToFree.free();
    }

    private void cancelScheduler() {
        ScheduledExecutorService current = scheduler;
        scheduler = null;
        if (current != null) current.shutdownNow();
    }

    private void ensurePrepared() {
        if (agent == null || stream == null || component == null) {
            throw new IllegalStateException("ICE connector has not been prepared");
        }
    }

    private static Description createDescription(Agent agent, Component component) {
        Description description = new Description();
        description.protocol = PROTOCOL;
        description.ufrag = agent.getLocalUfrag();
        description.password = agent.getLocalPassword();
        description.candidates = new ArrayList<>();

        for (LocalCandidate local : component.getLocalCandidates()) {
            String type = encodeDirectCandidateType(local.getType());
            if (type == null || local.getTransport() != Transport.UDP) continue;
            TransportAddress address = local.getTransportAddress();
            if (address == null || address.getAddress() == null) continue;

            CandidateInfo info = new CandidateInfo();
            info.foundation = safeFoundation(local.getFoundation());
            info.ip = address.getAddress().getHostAddress();
            info.port = address.getPort();
            info.priority = normalizePriority(local.getPriority());
            info.type = type;
            description.candidates.add(info);
        }
        description.candidates.sort(Comparator
                .comparingLong((CandidateInfo value) -> value.priority)
                .reversed()
                .thenComparing(value -> value.ip)
                .thenComparingInt(value -> value.port));
        if (description.candidates.isEmpty()) {
            throw new IllegalStateException("ICE gathered no direct UDP candidates");
        }
        return description;
    }

    private static List<InetSocketAddress> collectPublicAddresses(Component component) {
        Set<InetSocketAddress> addresses = new LinkedHashSet<>();
        for (LocalCandidate local : component.getLocalCandidates()) {
            if (local.getType() != CandidateType.SERVER_REFLEXIVE_CANDIDATE
                    && local.getType() != CandidateType.STUN_CANDIDATE) {
                continue;
            }
            TransportAddress address = local.getTransportAddress();
            if (address != null && address.getAddress() != null) {
                addresses.add(new InetSocketAddress(address.getAddress(), address.getPort()));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(addresses));
    }

    private static String encode(Description description) throws Exception {
        byte[] json = MAPPER.writeValueAsBytes(description);
        return DESCRIPTION_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    private static Description decode(String value) throws Exception {
        if (!isDescription(value)) {
            throw new IllegalArgumentException("unsupported ICE protocol description");
        }
        String payload = value.substring(DESCRIPTION_PREFIX.length());
        if (payload.length() > 256 * 1024) {
            throw new IllegalArgumentException("ICE description is too large");
        }
        byte[] json = Base64.getUrlDecoder().decode(payload.getBytes(StandardCharsets.US_ASCII));
        Description description = MAPPER.readValue(json, Description.class);
        if (description == null
                || !PROTOCOL.equals(description.protocol)
                || description.ufrag == null
                || description.ufrag.length() < 4
                || description.password == null
                || description.password.length() < 22
                || description.candidates == null
                || description.candidates.isEmpty()) {
            throw new IllegalArgumentException("invalid ICE description");
        }
        if (description.candidates.size() > MAX_REMOTE_CANDIDATES) {
            description.candidates = new ArrayList<>(
                    description.candidates.subList(0, MAX_REMOTE_CANDIDATES));
        }
        return description;
    }

    private static String summarizeCandidates(List<CandidateInfo> candidates) {
        int host = 0;
        int srflx = 0;
        int prflx = 0;
        for (CandidateInfo candidate : candidates) {
            if ("host".equals(candidate.type)) host++;
            else if ("srflx".equals(candidate.type)) srflx++;
            else if ("prflx".equals(candidate.type)) prflx++;
        }
        return "candidates=" + candidates.size()
                + "{host=" + host + ", srflx=" + srflx + ", prflx=" + prflx + "}";
    }

    private static String describePair(CandidatePair pair) {
        return "local=" + pair.getLocalCandidate().getType()
                + "/" + pair.getLocalCandidate().getTransportAddress()
                + " <-> remote=" + pair.getRemoteCandidate().getType()
                + "/" + pair.getRemoteCandidate().getTransportAddress()
                + ", nominated=" + pair.isNominated()
                + ", state=" + pair.getState();
    }

    private static String encodeDirectCandidateType(CandidateType type) {
        if (type == CandidateType.HOST_CANDIDATE) return "host";
        if (type == CandidateType.SERVER_REFLEXIVE_CANDIDATE
                || type == CandidateType.STUN_CANDIDATE) return "srflx";
        if (type == CandidateType.PEER_REFLEXIVE_CANDIDATE) return "prflx";
        // RELAYED_CANDIDATE is deliberately excluded.
        return null;
    }

    private static CandidateType parseDirectCandidateType(String type) {
        if (type == null) return null;
        String normalized = type.toLowerCase(Locale.ROOT);
        if ("host".equals(normalized)) return CandidateType.HOST_CANDIDATE;
        if ("srflx".equals(normalized)) return CandidateType.SERVER_REFLEXIVE_CANDIDATE;
        if ("prflx".equals(normalized)) return CandidateType.PEER_REFLEXIVE_CANDIDATE;
        // relay is never accepted, even if a peer sends it.
        return null;
    }

    private static long normalizePriority(long priority) {
        if (priority < 1) return 1;
        return Math.min(priority, 0xFFFFFFFFL);
    }

    private static String safeFoundation(String foundation) {
        if (foundation == null || foundation.trim().isEmpty()) return "1";
        String value = foundation.trim();
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private static String stripIpv6Brackets(String value) {
        if (value.startsWith("[") && value.endsWith("]")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void requireSupportedJava() {
        String specification = System.getProperty("java.specification.version", "0");
        int major;
        try {
            major = specification.startsWith("1.")
                    ? Integer.parseInt(specification.substring(2))
                    : Integer.parseInt(specification.split("\\.")[0]);
        } catch (NumberFormatException e) {
            major = 0;
        }
        if (major < 11) {
            throw new IllegalStateException(
                    "远程桌面完整 ICE 直连要求 JDK 11+，当前 Java=" + specification);
        }
    }

    public static final class Description {
        public String protocol;
        public String ufrag;
        public String password;
        public List<CandidateInfo> candidates;

        public Description() {
        }
    }

    public static final class CandidateInfo {
        public String foundation;
        public String ip;
        public int port;
        public long priority;
        public String type;

        public CandidateInfo() {
        }

        @Override
        public String toString() {
            String printableIp = ip != null && ip.indexOf(':') >= 0 ? "[" + ip + "]" : ip;
            return type + " " + printableIp + ":" + port
                    + " priority=" + priority + " foundation=" + foundation;
        }
    }

    private static final class ManagedIceChannel implements DesktopChannel {
        private final Ice4jDirectConnector owner;
        private final long generation;
        private final UdpChannelImpl delegate;
        private volatile Runnable closeListener;

        private ManagedIceChannel(Ice4jDirectConnector owner,
                                  long generation,
                                  UdpChannelImpl delegate) {
            this.owner = owner;
            this.generation = generation;
            this.delegate = delegate;
            this.delegate.setCloseListener(() -> {
                owner.onChannelClosed(generation, this);
                Runnable listener = closeListener;
                if (listener != null) listener.run();
            });
        }

        @Override
        public void send(DesktopMessage message) {
            delegate.send(message);
        }

        @Override
        public void setMessageListener(Consumer<DesktopMessage> listener) {
            delegate.setMessageListener(listener);
        }

        @Override
        public void setCloseListener(Runnable listener) {
            closeListener = listener;
        }

        @Override
        public boolean isP2P() {
            return true;
        }

        @Override
        public String getStatusDescription() {
            return "UDP 直连通道 (完整 ICE，未使用 TURN/中转)";
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
