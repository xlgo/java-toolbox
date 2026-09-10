package com.aqishi.toolbox.feature.security.domain;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.Attribute;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.operator.ContentVerifierProvider;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * CSR 证书签名请求解析、PKCS#12 密钥库检查与证书链验证服务。
 */
public class CertInspectorService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss z")
            .withZone(ZoneId.systemDefault());

    /**
     * CSR 解析结果模型。
     */
    public static class CsrInfo {
        private String subject = "";
        private String signatureAlgorithm = "";
        private String publicKeyAlgorithm = "";
        private int keySize;
        private boolean signatureValid;
        private List<String> sanList = new ArrayList<>();
        private Map<String, String> attributes = new LinkedHashMap<>();

        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getSignatureAlgorithm() { return signatureAlgorithm; }
        public void setSignatureAlgorithm(String signatureAlgorithm) { this.signatureAlgorithm = signatureAlgorithm; }
        public String getPublicKeyAlgorithm() { return publicKeyAlgorithm; }
        public void setPublicKeyAlgorithm(String publicKeyAlgorithm) { this.publicKeyAlgorithm = publicKeyAlgorithm; }
        public int getKeySize() { return keySize; }
        public void setKeySize(int keySize) { this.keySize = keySize; }
        public boolean isSignatureValid() { return signatureValid; }
        public void setSignatureValid(boolean signatureValid) { this.signatureValid = signatureValid; }
        public List<String> getSanList() { return sanList; }
        public void setSanList(List<String> sanList) { this.sanList = sanList; }
        public Map<String, String> getAttributes() { return attributes; }
    }

    /**
     * PKCS#12 条目信息模型。
     */
    public static class Pkcs12EntryInfo {
        private String alias;
        private boolean hasPrivateKey;
        private int chainLength;
        private String subject = "";
        private String issuer = "";
        private String notBefore = "";
        private String notAfter = "";
        private String keyAlg = "";
        private X509Certificate certificate;
        private List<X509Certificate> chain = new ArrayList<>();
        private PrivateKey privateKey;

        public String getAlias() { return alias; }
        public void setAlias(String alias) { this.alias = alias; }
        public boolean isHasPrivateKey() { return hasPrivateKey; }
        public void setHasPrivateKey(boolean hasPrivateKey) { this.hasPrivateKey = hasPrivateKey; }
        public int getChainLength() { return chainLength; }
        public void setChainLength(int chainLength) { this.chainLength = chainLength; }
        public String getSubject() { return subject; }
        public void setSubject(String subject) { this.subject = subject; }
        public String getIssuer() { return issuer; }
        public void setIssuer(String issuer) { this.issuer = issuer; }
        public String getNotBefore() { return notBefore; }
        public void setNotBefore(String notBefore) { this.notBefore = notBefore; }
        public String getNotAfter() { return notAfter; }
        public void setNotAfter(String notAfter) { this.notAfter = notAfter; }
        public String getKeyAlg() { return keyAlg; }
        public void setKeyAlg(String keyAlg) { this.keyAlg = keyAlg; }
        public X509Certificate getCertificate() { return certificate; }
        public void setCertificate(X509Certificate certificate) { this.certificate = certificate; }
        public List<X509Certificate> getChain() { return chain; }
        public void setChain(List<X509Certificate> chain) { this.chain = chain; }
        public PrivateKey getPrivateKey() { return privateKey; }
        public void setPrivateKey(PrivateKey privateKey) { this.privateKey = privateKey; }

        @Override
        public String toString() {
            return alias + " (" + (hasPrivateKey ? "私钥 + 证书链" : "信任证书") + ")";
        }
    }

    /**
     * 证书链校验评估结果模型。
     */
    public static class ChainValidationResult {
        private boolean valid;
        private final List<String> logs = new ArrayList<>();
        private final List<X509Certificate> sortedChain = new ArrayList<>();

        public boolean isValid() { return valid; }
        public void setValid(boolean valid) { this.valid = valid; }
        public List<String> getLogs() { return logs; }
        public List<X509Certificate> getSortedChain() { return sortedChain; }
    }

    /**
     * 解析 CSR (PKCS#10) 证书签名请求。
     */
    public CsrInfo parseCsr(String csrPem) throws Exception {
        if (csrPem == null || csrPem.trim().isEmpty()) {
            throw new IllegalArgumentException("CSR 内容不能为空");
        }

        PKCS10CertificationRequest req;
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            Object obj = parser.readObject();
            if (obj instanceof PKCS10CertificationRequest) {
                req = (PKCS10CertificationRequest) obj;
            } else {
                throw new IllegalArgumentException("文本内容不是合法的 PKCS#10 CSR 格式");
            }
        }

        CsrInfo info = new CsrInfo();
        X500Name subject = req.getSubject();
        info.setSubject(subject != null ? subject.toString() : "");
        info.setSignatureAlgorithm(req.getSignatureAlgorithm().getAlgorithm().getId());

        // 公钥提取与校验
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
        PublicKey publicKey = converter.getPublicKey(req.getSubjectPublicKeyInfo());
        if (publicKey instanceof RSAPublicKey) {
            info.setPublicKeyAlgorithm("RSA");
            info.setKeySize(((RSAPublicKey) publicKey).getModulus().bitLength());
        } else if (publicKey instanceof ECPublicKey) {
            info.setPublicKeyAlgorithm("EC");
            info.setKeySize(((ECPublicKey) publicKey).getParams().getCurve().getField().getFieldSize());
        } else {
            info.setPublicKeyAlgorithm(publicKey.getAlgorithm());
            info.setKeySize(0);
        }

        // 自签名校验（Proof-of-Possession 证明拥有对应私钥）
        try {
            ContentVerifierProvider verifierProvider = new JcaContentVerifierProviderBuilder().build(publicKey);
            info.setSignatureValid(req.isSignatureValid(verifierProvider));
        } catch (Exception ex) {
            info.setSignatureValid(false);
        }

        // 提取属性与 SAN 扩展项
        Attribute[] attributes = req.getAttributes();
        if (attributes != null) {
            for (Attribute attr : attributes) {
                ASN1ObjectIdentifier oid = attr.getAttrType();
                if (PKCSObjectIdentifiers.pkcs_9_at_extensionRequest.equals(oid)) {
                    Extensions extensions = Extensions.getInstance(attr.getAttributeValues()[0]);
                    Extension sanExt = extensions.getExtension(Extension.subjectAlternativeName);
                    if (sanExt != null) {
                        GeneralNames gns = GeneralNames.getInstance(sanExt.getParsedValue());
                        for (org.bouncycastle.asn1.x509.GeneralName gn : gns.getNames()) {
                            info.getSanList().add(gn.getName().toString());
                        }
                    }
                } else {
                    info.getAttributes().put(oid.getId(), attr.getAttributeValues()[0].toString());
                }
            }
        }

        return info;
    }

    /**
     * 检查并解析 PKCS#12 密钥库 (.p12 / .pfx)。
     */
    public List<Pkcs12EntryInfo> inspectPkcs12(byte[] p12Bytes, char[] password) throws Exception {
        if (p12Bytes == null || p12Bytes.length == 0) {
            throw new IllegalArgumentException("PKCS#12 数据不能为空");
        }

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream is = new ByteArrayInputStream(p12Bytes)) {
            ks.load(is, password != null ? password : new char[0]);
        }

        List<Pkcs12EntryInfo> list = new ArrayList<>();
        Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            Pkcs12EntryInfo entry = new Pkcs12EntryInfo();
            entry.setAlias(alias);

            boolean isKey = ks.isKeyEntry(alias);
            entry.setHasPrivateKey(isKey);

            Certificate[] certChain = ks.getCertificateChain(alias);
            Certificate cert = ks.getCertificate(alias);

            if (certChain != null && certChain.length > 0) {
                entry.setChainLength(certChain.length);
                for (Certificate c : certChain) {
                    if (c instanceof X509Certificate) {
                        entry.getChain().add((X509Certificate) c);
                    }
                }
            } else if (cert != null) {
                entry.setChainLength(1);
                if (cert instanceof X509Certificate) {
                    entry.getChain().add((X509Certificate) cert);
                }
            }

            if (!entry.getChain().isEmpty()) {
                X509Certificate head = entry.getChain().get(0);
                entry.setCertificate(head);
                entry.setSubject(head.getSubjectX500Principal().getName());
                entry.setIssuer(head.getIssuerX500Principal().getName());
                entry.setNotBefore(formatDate(head.getNotBefore()));
                entry.setNotAfter(formatDate(head.getNotAfter()));
                entry.setKeyAlg(head.getPublicKey().getAlgorithm());
            }

            if (isKey) {
                try {
                    Key key = ks.getKey(alias, password != null ? password : new char[0]);
                    if (key instanceof PrivateKey) {
                        entry.setPrivateKey((PrivateKey) key);
                    }
                } catch (Exception ignored) {}
            }
            list.add(entry);
        }
        return list;
    }

    /**
     * 校验由多个 PEM 证书构成的证书链。
     */
    public ChainValidationResult validateCertificateChainFromPem(String pemsContent) throws Exception {
        if (pemsContent == null || pemsContent.trim().isEmpty()) {
            throw new IllegalArgumentException("证书内容不能为空");
        }

        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = cf.generateCertificates(
                new ByteArrayInputStream(pemsContent.getBytes(StandardCharsets.UTF_8)));

        List<X509Certificate> chain = new ArrayList<>();
        for (Certificate c : certs) {
            if (c instanceof X509Certificate) {
                chain.add((X509Certificate) c);
            }
        }
        return validateCertificateChain(chain);
    }

    /**
     * 逐级验证证书链完整性（自动排序、逐级签名校验、有效期限比对、CA 约束检查）。
     */
    public ChainValidationResult validateCertificateChain(List<X509Certificate> certs) {
        ChainValidationResult res = new ChainValidationResult();
        if (certs == null || certs.isEmpty()) {
            res.setValid(false);
            res.getLogs().add("错误: 证书链为空");
            return res;
        }

        // 1. 尝试排序证书链（从叶子证书到根证书）
        List<X509Certificate> sorted = sortChain(certs);
        res.getSortedChain().addAll(sorted);

        res.getLogs().add("【证书链总节点数】: " + sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            X509Certificate c = sorted.get(i);
            res.getLogs().add(String.format("  Level %d: Subject: %s", i, c.getSubjectX500Principal().getName()));
            res.getLogs().add(String.format("           Issuer:  %s", c.getIssuerX500Principal().getName()));
            res.getLogs().add(String.format("           有效期: %s 至 %s",
                    formatDate(c.getNotBefore()), formatDate(c.getNotAfter())));
        }

        boolean allValid = true;

        // 2. 检查每一级证书的有效期
        Date now = new Date();
        for (int i = 0; i < sorted.size(); i++) {
            X509Certificate c = sorted.get(i);
            try {
                c.checkValidity(now);
                res.getLogs().add(String.format("✓ Level %d 证书在有效期内", i));
            } catch (Exception ex) {
                res.getLogs().add(String.format("✗ Level %d 证书有效期异常: %s", i, ex.getMessage()));
                allValid = false;
            }
        }

        // 3. 逐级验证签名
        for (int i = 0; i < sorted.size() - 1; i++) {
            X509Certificate child = sorted.get(i);
            X509Certificate parent = sorted.get(i + 1);

            try {
                child.verify(parent.getPublicKey());
                res.getLogs().add(String.format("✓ Level %d 证书签名由上级 Level %d 签发验证通过", i, i + 1));
            } catch (Exception ex) {
                res.getLogs().add(String.format("✗ Level %d 签名验证失败（非 Level %d 公钥签发）: %s", i, i + 1, ex.getMessage()));
                allValid = false;
            }

            // 检查中间证书是否有 CA 扩展
            if (parent.getBasicConstraints() < 0) {
                res.getLogs().add(String.format("⚠ Level %d 证书缺少 CA 约束 (BasicConstraints isCA != true)", i + 1));
            }
        }

        // 4. 根证书自签验证
        X509Certificate root = sorted.get(sorted.size() - 1);
        if (root.getSubjectX500Principal().equals(root.getIssuerX500Principal())) {
            try {
                root.verify(root.getPublicKey());
                res.getLogs().add("✓ 根证书 (Root CA) 为有效自签证书 (Self-Signed Root)");
            } catch (Exception ex) {
                res.getLogs().add("✗ 根证书自签校验失败: " + ex.getMessage());
                allValid = false;
            }
        } else {
            res.getLogs().add("ℹ 最上级证书非自签根证书（证书链可能未包含 Root CA）");
        }

        res.setValid(allValid);
        return res;
    }

    private List<X509Certificate> sortChain(List<X509Certificate> input) {
        if (input.size() <= 1) return new ArrayList<>(input);
        List<X509Certificate> list = new ArrayList<>(input);

        // 找到叶子节点（没有其它证书以它为 Issuer 的证书）
        X509Certificate leaf = null;
        for (X509Certificate candidate : list) {
            boolean isIssuerForSomeone = false;
            for (X509Certificate other : list) {
                if (other != candidate && other.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                    isIssuerForSomeone = true;
                    break;
                }
            }
            if (!isIssuerForSomeone) {
                leaf = candidate;
                break;
            }
        }
        if (leaf == null) leaf = list.get(0);

        List<X509Certificate> sorted = new ArrayList<>();
        sorted.add(leaf);
        X509Certificate current = leaf;

        while (sorted.size() < list.size()) {
            X509Certificate parent = null;
            for (X509Certificate candidate : list) {
                if (!sorted.contains(candidate) && current.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) {
                    parent = candidate;
                    break;
                }
            }
            if (parent == null) break;
            sorted.add(parent);
            current = parent;
        }

        // 如果仍有未包含的，追加在末尾
        for (X509Certificate c : list) {
            if (!sorted.contains(c)) sorted.add(c);
        }
        return sorted;
    }

    private static String formatDate(Date value) {
        return DATE_FMT.format(value.toInstant());
    }
}
