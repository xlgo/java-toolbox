package com.aqishi.toolbox.crypto;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * RSA 非对称加密与签名工具类。
 * 支持密钥对生成、公钥加密/私钥解密、私钥签名/公钥验签。
 */
public final class RSAUtils {

    private RSAUtils() {
    }

    public static class RSAKeyPair {
        public final String publicKey;
        public final String privateKey;

        public RSAKeyPair(String publicKey, String privateKey) {
            this.publicKey = publicKey;
            this.privateKey = privateKey;
        }
    }

    /**
     * 生成 RSA 密钥对
     *
     * @param keySize 密钥大小 (1024, 2048, 4096 等)
     * @return RSAKeyPair，包含 Base64 编码的公钥和私钥
     */
    public static RSAKeyPair generateKeyPair(int keySize) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(keySize, new SecureRandom());
        KeyPair kp = kpg.generateKeyPair();
        String pub = Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
        String pri = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        return new RSAKeyPair(pub, pri);
    }

    /**
     * 公钥加密 (使用 RSA/ECB/PKCS1Padding)
     */
    public static String encrypt(String plainText, String publicKeyBase64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64.trim());
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pubKey = kf.generatePublic(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.ENCRYPT_MODE, pubKey);

        byte[] plainBytes = plainText.getBytes(StandardCharsets.UTF_8);
        byte[] encryptedBytes = cipher.doFinal(plainBytes);
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    /**
     * 私钥解密 (使用 RSA/ECB/PKCS1Padding)
     */
    public static String decrypt(String cipherTextBase64, String privateKeyBase64) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64.trim());
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priKey = kf.generatePrivate(spec);

        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(Cipher.DECRYPT_MODE, priKey);

        byte[] cipherBytes = Base64.getDecoder().decode(cipherTextBase64.trim());
        byte[] plainBytes = cipher.doFinal(cipherBytes);
        return new String(plainBytes, StandardCharsets.UTF_8);
    }

    /**
     * 私钥签名
     *
     * @param plainText        待签名消息
     * @param privateKeyBase64 私钥 (Base64)
     * @param signatureAlgorithm 签名算法 ("MD5withRSA", "SHA1withRSA", "SHA256withRSA")
     */
    public static String sign(String plainText, String privateKeyBase64, String signatureAlgorithm) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(privateKeyBase64.trim());
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PrivateKey priKey = kf.generatePrivate(spec);

        Signature sig = Signature.getInstance(signatureAlgorithm);
        sig.initSign(priKey);
        sig.update(plainText.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(sig.sign());
    }

    /**
     * 公钥验签
     *
     * @param plainText       待验签消息
     * @param signatureBase64 签名值 (Base64)
     * @param publicKeyBase64  公钥 (Base64)
     * @param signatureAlgorithm 签名算法 ("MD5withRSA", "SHA1withRSA", "SHA256withRSA")
     */
    public static boolean verify(String plainText, String signatureBase64, String publicKeyBase64, String signatureAlgorithm) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(publicKeyBase64.trim());
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        PublicKey pubKey = kf.generatePublic(spec);

        Signature sig = Signature.getInstance(signatureAlgorithm);
        sig.initVerify(pubKey);
        sig.update(plainText.getBytes(StandardCharsets.UTF_8));
        return sig.verify(Base64.getDecoder().decode(signatureBase64.trim()));
    }
}
