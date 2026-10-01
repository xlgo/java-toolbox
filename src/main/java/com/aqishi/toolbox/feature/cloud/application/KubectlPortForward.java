package com.aqishi.toolbox.feature.cloud.application;

import com.aqishi.toolbox.infra.concurrency.DaemonThreads;
import com.aqishi.toolbox.util.I18n;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/** One kubectl child owns one loopback TCP forwarding session; stopping never blocks the Swing thread. */
public final class KubectlPortForward implements AutoCloseable {
    public record Target(String namespace, String kind, String name, int localPort, String remotePort) {
        public Target {
            if(namespace==null || !namespace.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?") || namespace.length()>63
                    || name==null || !name.matches("[a-z0-9]([-a-z0-9.]*[a-z0-9])?") || name.length()>253
                    || !Set.of("pod","service","deployment").contains(kind)) throw new IllegalArgumentException(I18n.get("k8s.forward.targetInvalid"));
            if(localPort<0 || localPort>65535 || remotePort==null || !remotePort.matches("[a-z0-9-]+"))
                throw new IllegalArgumentException(I18n.get("k8s.forward.portInvalid"));
            if(remotePort.matches("[0-9]+")) {
                long port;
                try { port=Long.parseLong(remotePort); } catch(NumberFormatException invalid) { port=0; }
                if(port<1 || port>65535) throw new IllegalArgumentException(I18n.get("k8s.forward.portInvalid"));
            }
        }
    }
    public enum State { STARTING, RUNNING, STOPPED, FAILED }
    public record Snapshot(State state, int localPort, String log) { }
    @FunctionalInterface public interface Launcher { Process start(List<String> command) throws IOException; }
    private static final ScheduledExecutorService TIMER=Executors.newSingleThreadScheduledExecutor(DaemonThreads.factory("k8s-forward-timeout"));
    private static final Pattern FORWARD=Pattern.compile("Forwarding from 127\\.0\\.0\\.1:(\\d+) -> .*" );
    private final Target target;
    private final Launcher launcher;
    private final String executable;
    private final Deque<String> logs=new ArrayDeque<>();
    private volatile State state=State.STARTING;
    private volatile int actualPort;
    private volatile boolean closed;
    private volatile Process process;
    private volatile ScheduledFuture<?> startupTimeout;
    private boolean started;

    public KubectlPortForward(String executable, Target target) {
        this(executable,target,command -> new ProcessBuilder(command).redirectErrorStream(true).start());
    }
    public KubectlPortForward(String executable, Target target, Launcher launcher) {
        this.executable=executable; this.target=Objects.requireNonNull(target); this.launcher=Objects.requireNonNull(launcher);
    }
    public Target target() { return target; }
    public synchronized Snapshot snapshot() { return new Snapshot(state,actualPort,String.join("\n",logs)); }
    public static List<String> command(String executable, Path config, Target target) {
        if(executable==null || executable.isBlank()) throw new IllegalArgumentException(I18n.get("k8s.forward.executable"));
        return List.of(executable,"--kubeconfig",config.toString(),"--namespace",target.namespace(),"port-forward",
                target.kind()+"/"+target.name(),(target.localPort()==0?"":target.localPort())+":"+target.remotePort(),
                "--address=127.0.0.1","--pod-running-timeout=20s");
    }
    public synchronized void start(PortForwardConfig.Credentials credentials) {
        if(started || closed) throw new IllegalStateException("Forwarding session already started or closed");
        started=true;
        DaemonThreads.factory("k8s-port-forward").newThread(() -> run(credentials)).start();
    }
    private void run(PortForwardConfig.Credentials credentials) {
        try(PortForwardConfig config=PortForwardConfig.create(credentials)) {
            if(closed) return;
            Process child=launcher.start(command(executable,config.path(),target)); process=child;
            if(closed) { destroy(child); child.waitFor(); return; }
            startupTimeout=TIMER.schedule(this::startupExpired,30,TimeUnit.SECONDS);
            try(Reader reader=new InputStreamReader(child.getInputStream(),StandardCharsets.UTF_8)) {
                StringBuilder line=new StringBuilder(); int next;
                while((next=reader.read())!=-1) {
                    if(next=='\n') { acceptLine(line.toString(),credentials); line.setLength(0); }
                    else if(next!='\r' && line.length()<4096) line.append((char)next);
                }
                if(!line.isEmpty()) acceptLine(line.toString(),credentials);
            }
            int code=child.waitFor();
            if(!closed && state!=State.FAILED) { state=State.FAILED; log(I18n.get("k8s.forward.exited",code)); }
        } catch(Exception failure) {
            if(!closed) { state=State.FAILED; log(I18n.get("k8s.forward.failed",failure.getClass().getSimpleName())); }
        } finally {
            ScheduledFuture<?> timer=startupTimeout; if(timer!=null) timer.cancel(false);
            closeProcess(); if(closed) state=State.STOPPED;
        }
    }
    private synchronized void startupExpired() {
        if(state==State.STARTING && !closed) { log(I18n.get("k8s.forward.timeout")); state=State.FAILED; closeProcess(); }
    }
    private synchronized void acceptLine(String line,PortForwardConfig.Credentials credentials) {
        String safe=line;
        for(String secret:List.of(credentials.token(),credentials.key(),credentials.certificate())) {
            if(!secret.isBlank()) {
                safe=safe.replace(secret,"******");
                safe=safe.replace(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)),"******");
                for(String part:secret.split("\\R")) if(part.length()>8 && !part.startsWith("-----")) safe=safe.replace(part,"******");
            }
        }
        log(safe);
        Matcher match=FORWARD.matcher(line);
        if(match.matches() && !closed && state==State.STARTING) {
            int port=Integer.parseInt(match.group(1));
            if(port>0 && port<=65535) { actualPort=port; state=State.RUNNING; if(startupTimeout!=null)startupTimeout.cancel(false); }
        }
    }
    private synchronized void log(String line) { if(logs.size()>=200)logs.removeFirst(); logs.addLast(line); }
    private static void destroy(Process process) {
        process.destroy();
        TIMER.schedule(() -> { if(process.isAlive())process.destroyForcibly(); },1,TimeUnit.SECONDS);
    }
    private void closeProcess() { Process child=process; if(child!=null && child.isAlive())destroy(child); }
    @Override public void close() { closed=true; state=State.STOPPED; closeProcess(); }
}
