package com.aqishi.toolbox.feature.security.infra.acme;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/** The challenge provisioners behind the ACME tab's four validation modes. */
public final class ChallengeProvisioners {

    private ChallengeProvisioners() {
    }

    /**
     * DNS-01 through a DNS API: one TXT record per challenge, deleted on cleanup.
     * With a {@code lookup}, {@link ChallengeProvisioner#awaitVisible} polls it until every
     * record resolves (or {@code propagationPoller} times out).
     */
    public static ChallengeProvisioner dnsApi(DnsProvider provider, DnsTxtLookup lookup, AcmePoller propagationPoller) {
        return new DnsProvisioner(provider, lookup, propagationPoller);
    }

    /** DNS-01 with records added by the user: nothing to create or delete, optional visibility check. */
    public static ChallengeProvisioner manualDns(DnsTxtLookup lookup, AcmePoller propagationPoller) {
        return new DnsProvisioner(null, lookup, propagationPoller);
    }

    /** HTTP-01 answered by the built-in server on {@code port}; the server stops on close. */
    public static ChallengeProvisioner builtinHttp(int port) {
        return new ChallengeProvisioner() {
            @Override
            public String provision(AcmeClient.AcmeChallenge ch) throws Exception {
                AcmeChallengeHelper.startHttpServer(port);
                AcmeChallengeHelper.registerToken(ch.token, ch.keyAuthorization);
                return "http://" + ch.domain + "/.well-known/acme-challenge/" + ch.token;
            }

            @Override
            public void cleanup(AcmeClient.AcmeChallenge ch) {
                AcmeChallengeHelper.unregisterToken(ch.token);
            }

            @Override
            public void close() {
                AcmeChallengeHelper.stopHttpServer();
                AcmeChallengeHelper.clearTokens();
            }
        };
    }

    /** HTTP-01 by writing token files below {@code webRoot}; the files are deleted on cleanup. */
    public static ChallengeProvisioner webRoot(String webRoot) {
        Map<AcmeClient.AcmeChallenge, File> files = new ConcurrentHashMap<>();
        return new ChallengeProvisioner() {
            @Override
            public String provision(AcmeClient.AcmeChallenge ch) throws Exception {
                File file = AcmeChallengeHelper.writeChallengeToFile(webRoot, ch.token, ch.keyAuthorization);
                files.put(ch, file);
                return file.getAbsolutePath();
            }

            @Override
            public void cleanup(AcmeClient.AcmeChallenge ch) throws IOException {
                File file = files.remove(ch);
                if (file != null) {
                    Files.deleteIfExists(file.toPath());
                }
            }
        };
    }

    private static final class DnsProvisioner implements ChallengeProvisioner {
        private final DnsProvider provider;
        private final DnsTxtLookup lookup;
        private final AcmePoller poller;
        private final Map<AcmeClient.AcmeChallenge, String> recordIds = new ConcurrentHashMap<>();

        DnsProvisioner(DnsProvider provider, DnsTxtLookup lookup, AcmePoller poller) {
            this.provider = provider;
            this.lookup = lookup;
            this.poller = poller;
        }

        @Override
        public String provision(AcmeClient.AcmeChallenge ch) throws Exception {
            if (provider == null) {
                return ch.dnsTxtRecordName + " TXT " + ch.dnsTxtRecordValue;
            }
            String id = provider.addTxtRecord(ch.domain, ch.dnsTxtRecordName, ch.dnsTxtRecordValue);
            recordIds.put(ch, id);
            return ch.dnsTxtRecordName + " (id " + id + ")";
        }

        @Override
        public void cleanup(AcmeClient.AcmeChallenge ch) throws Exception {
            String id = recordIds.remove(ch);
            if (provider != null && id != null) {
                provider.deleteTxtRecord(ch.domain, id);
            }
        }

        @Override
        public boolean awaitVisible(List<AcmeClient.AcmeChallenge> challenges, BooleanSupplier cancelled) throws Exception {
            if (lookup == null || poller == null || challenges.isEmpty()) {
                return true;
            }
            try {
                return poller.poll("DNS propagation", cancelled, () -> {
                    for (AcmeClient.AcmeChallenge ch : challenges) {
                        List<String> values;
                        try {
                            values = lookup.lookup(ch.dnsTxtRecordName);
                        } catch (Exception resolverError) {
                            return AcmePoller.Attempt.again(null);
                        }
                        if (!values.contains(ch.dnsTxtRecordValue)) {
                            return AcmePoller.Attempt.again(null);
                        }
                    }
                    return AcmePoller.Attempt.done(Boolean.TRUE);
                });
            } catch (AcmeException e) {
                if (e.reason() == AcmeException.Reason.TIMEOUT) {
                    return false;
                }
                throw e;
            }
        }
    }
}
