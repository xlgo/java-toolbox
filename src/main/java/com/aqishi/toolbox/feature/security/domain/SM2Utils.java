package com.aqishi.toolbox.feature.security.domain;

import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.gm.GMNamedCurves;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.sec.ECPrivateKey;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.engines.SM2Engine;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.*;
import org.bouncycastle.crypto.signers.SM2Signer;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * SM2 国密非对称加密工具类。
 * <p>基于椭圆曲线 SM2 算法，支持：</p>
 * <ul>
 *   <li>密钥对生成（公钥/私钥）</li>
 *   <li>公钥加密 / 私钥解密</li>
 *   <li>私钥签名 / 公钥验签</li>
 * </ul>
 * <p>SM2 使用 256 位曲线，安全强度等同于 RSA-3072。</p>
 */
public final class SM2Utils {

    /** SM2 椭圆曲线名称 */
    private static final String CURVE_NAME = "sm2p256v1";

    /** SM2 曲线参数（懒加载初始化） */
    private static volatile X9ECParameters CURVE_PARAMS;

    /** 坐标字节长度 */
    private static final int COORD_SIZE = 32;

    private static X9ECParameters getCurveParams() {
        if (CURVE_PARAMS == null) {
            synchronized (SM2Utils.class) {
                if (CURVE_PARAMS == null) {
                    CURVE_PARAMS = GMNamedCurves.getByName(CURVE_NAME);
                }
            }
        }
        return CURVE_PARAMS;
    }

    private static ECDomainParameters getDomainParams() {
        X9ECParameters p = getCurveParams();
        return new ECDomainParameters(p.getCurve(), p.getG(), p.getN(), p.getH());
    }

    private SM2Utils() {
    }

    /**
     * SM2 密钥对封装。
     */
    public static class SM2KeyPair {
        /** 十六进制私钥（32 字节 D 值） */
        public final String privateKey;
        /** 十六进制公钥（04||x||y 非压缩格式，130 字符） */
        public final String publicKey;

        public SM2KeyPair(String privateKey, String publicKey) {
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }

    /**
     * 生成 SM2 密钥对（使用 BouncyCastle 轻量级 API，无需 JCE）。
     *
     * @return SM2KeyPair 包含十六进制私钥和公钥
     */
    public static SM2KeyPair generateKeyPair() {
        try {
            ECDomainParameters domainParams = getDomainParams();
            ECKeyPairGenerator generator = new ECKeyPairGenerator();
            generator.init(new ECKeyGenerationParameters(domainParams, new SecureRandom()));
            AsymmetricCipherKeyPair kp = generator.generateKeyPair();

            ECPrivateKeyParameters priv = (ECPrivateKeyParameters) kp.getPrivate();
            ECPublicKeyParameters pub  = (ECPublicKeyParameters) kp.getPublic();

            // 私钥 D 值：32 字节
            String pri = Hex.toHexString(fixLength(priv.getD().toByteArray(), COORD_SIZE));

            // 公钥：非压缩点 04||x||y，各 32 字节
            ECPoint q = pub.getQ().normalize();
            byte[] xBytes = fixLength(q.getAffineXCoord().toBigInteger().toByteArray(), COORD_SIZE);
            byte[] yBytes = fixLength(q.getAffineYCoord().toBigInteger().toByteArray(), COORD_SIZE);
            byte[] rawPub = new byte[1 + COORD_SIZE * 2];
            rawPub[0] = 0x04;
            System.arraycopy(xBytes, 0, rawPub, 1, COORD_SIZE);
            System.arraycopy(yBytes, 0, rawPub, 1 + COORD_SIZE, COORD_SIZE);
            String pubStr = Hex.toHexString(rawPub);

            return new SM2KeyPair(pri, pubStr);
        } catch (Exception e) {
            throw new RuntimeException("SM2 密钥对生成失败：" + e.getMessage(), e);
        }
    }

    // ==================== 加解密 ====================

    /**
     * SM2 公钥加密。
     *
     * @param plainText   明文字符串
     * @param publicKeyHex 十六进制公钥（非压缩格式或 DER 格式）
     * @return Base64 编码的密文
     */
    public static String encrypt(String plainText, String publicKeyHex) {
        try {
            byte[] plainData = plainText.getBytes(StandardCharsets.UTF_8);
            byte[] cipherData = encrypt(plainData, publicKeyHex);
            return Base64.getEncoder().encodeToString(cipherData);
        } catch (Exception e) {
            throw new RuntimeException("SM2 加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * SM2 公钥加密（字节数组）。
     *
     * @param plainData    明文数据
     * @param publicKeyHex 十六进制公钥
     * @return 密文字节数组
     */
    public static byte[] encrypt(byte[] plainData, String publicKeyHex) {
        try {
            ECPublicKeyParameters pubKey = buildPublicKeyParams(publicKeyHex);
            SM2Engine engine = new SM2Engine();
            engine.init(true, new ParametersWithRandom(pubKey, new SecureRandom()));
            return engine.processBlock(plainData, 0, plainData.length);
        } catch (Exception e) {
            throw new RuntimeException("SM2 加密失败：" + e.getMessage(), e);
        }
    }

    /**
     * SM2 私钥解密。
     *
     * @param cipherBase64  Base64 编码的密文
     * @param privateKeyHex 十六进制私钥
     * @return 明文字符串
     */
    public static String decrypt(String cipherBase64, String privateKeyHex) {
        try {
            byte[] cipherData = Base64.getDecoder().decode(cipherBase64);
            byte[] plainData = decrypt(cipherData, privateKeyHex);
            return new String(plainData, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("SM2 解密失败：" + e.getMessage(), e);
        }
    }

    /**
     * SM2 私钥解密（字节数组）。
     *
     * @param cipherData    密文数据
     * @param privateKeyHex 十六进制私钥
     * @return 明文数据
     */
    public static byte[] decrypt(byte[] cipherData, String privateKeyHex) {
        try {
            ECPrivateKeyParameters priKey = buildPrivateKeyParams(privateKeyHex);
            SM2Engine engine = new SM2Engine();
            engine.init(false, priKey);
            return engine.processBlock(cipherData, 0, cipherData.length);
        } catch (Exception e) {
            throw new RuntimeException("SM2 解密失败：" + e.getMessage(), e);
        }
    }

    // ==================== 签名验签 ====================

    /**
     * SM2 签名。
     *
     * @param message       待签名消息
     * @param privateKeyHex 十六进制私钥
     * @return Base64 编码的签名
     */
    public static String sign(String message, String privateKeyHex) {
        try {
            ECPrivateKeyParameters priKey = buildPrivateKeyParams(privateKeyHex);

            // 使用 userId 的默认值 "1234567812345678"
            byte[] userId = "1234567812345678".getBytes(StandardCharsets.UTF_8);
            SM2Signer signer = new SM2Signer();
            signer.init(true, new ParametersWithID(new ParametersWithRandom(priKey, new SecureRandom()), userId));

            byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
            signer.update(msgBytes, 0, msgBytes.length);
            byte[] sig = signer.generateSignature();
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception e) {
            throw new RuntimeException("SM2 签名失败：" + e.getMessage(), e);
        }
    }

    /**
     * SM2 验签。
     *
     * @param message         原始消息
     * @param signatureBase64 Base64 编码的签名
     * @param publicKeyHex    十六进制公钥
     * @return true 表示验签通过
     */
    public static boolean verify(String message, String signatureBase64, String publicKeyHex) {
        try {
            byte[] sig = Base64.getDecoder().decode(signatureBase64);
            ECPublicKeyParameters pubKey = buildPublicKeyParams(publicKeyHex);

            byte[] userId = "1234567812345678".getBytes(StandardCharsets.UTF_8);
            SM2Signer signer = new SM2Signer();
            signer.init(false, new ParametersWithID(pubKey, userId));

            byte[] msgBytes = message.getBytes(StandardCharsets.UTF_8);
            signer.update(msgBytes, 0, msgBytes.length);
            return signer.verifySignature(sig);
        } catch (Exception e) {
            throw new RuntimeException("SM2 验签失败：" + e.getMessage(), e);
        }
    }

    // ==================== 内部辅助方法 ====================

    /**
     * 根据十六进制公钥字符串构建 ECPublicKeyParameters。
     * 支持非压缩格式（04||x||y）和 X.509 SubjectPublicKeyInfo DER 格式。
     */
    private static ECPublicKeyParameters buildPublicKeyParams(String publicKeyHex) {
        byte[] keyBytes = Hex.decode(publicKeyHex);
        X9ECParameters params = getCurveParams();
        ECDomainParameters domainParams = getDomainParams();

        ECPoint pubPoint;
        if (keyBytes[0] == 0x04) {
            pubPoint = params.getCurve().decodePoint(keyBytes);
        } else if (keyBytes[0] == 0x30) {
            pubPoint = extractPointFromSubjectPublicKeyInfo(keyBytes, params);
        } else {
            throw new IllegalArgumentException("不支持的密钥格式，首字节: 0x"
                    + Integer.toHexString(keyBytes[0] & 0xff));
        }
        return new ECPublicKeyParameters(pubPoint, domainParams);
    }

    private static ECPrivateKeyParameters buildPrivateKeyParams(String privateKeyHex) {
        byte[] keyBytes = Hex.decode(privateKeyHex);
        ECDomainParameters domainParams = getDomainParams();

        BigInteger d;
        if (keyBytes.length == COORD_SIZE) {
            d = new BigInteger(1, keyBytes);
        } else {
            d = new BigInteger(1, extractPrivateKeyD(keyBytes));
        }
        return new ECPrivateKeyParameters(d, domainParams);
    }

    /**
     * 从 X.509 SubjectPublicKeyInfo（{@code 3059...}）中取出公钥点 {@code 04||x||y}。
     *
     * <p>按 ASN.1 结构解析，而不是在字节里找第一个 {@code 0x04}：DER 的长度、OID 字节
     * 都可能恰好等于 0x04，扫描会取到错误的 65 字节并得到一个错误（或非法）的点。</p>
     */
    private static ECPoint extractPointFromSubjectPublicKeyInfo(byte[] spki, X9ECParameters params) {
        try {
            byte[] point = SubjectPublicKeyInfo.getInstance(spki).getPublicKeyData().getOctets();
            return params.getCurve().decodePoint(point);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("无法解析 SubjectPublicKeyInfo 公钥: " + malformed.getMessage(), malformed);
        }
    }

    /**
     * 从 DER 编码的私钥中取出 D 值，支持 PKCS#8（{@code PrivateKeyInfo}）与 SEC1（{@code ECPrivateKey}）。
     *
     * <p>早先直接取"最后 32 字节"：两种编码都会在 D 之后附带可选的曲线参数与公钥，
     * 最后 32 字节通常是公钥 y 坐标，于是用错误的私钥解密、签名而不报错。</p>
     */
    private static byte[] extractPrivateKeyD(byte[] encoded) {
        try {
            ASN1Sequence sequence = ASN1Sequence.getInstance(encoded);
            ECPrivateKey key;
            // PKCS#8 的第二个元素是算法标识（SEQUENCE）；SEC1 的第二个元素直接是 D（OCTET STRING）。
            if (sequence.size() >= 3 && sequence.getObjectAt(1) instanceof ASN1Sequence) {
                key = ECPrivateKey.getInstance(PrivateKeyInfo.getInstance(sequence).parsePrivateKey());
            } else {
                key = ECPrivateKey.getInstance(sequence);
            }
            return fixLength(key.getKey().toByteArray(), COORD_SIZE);
        } catch (java.io.IOException | RuntimeException malformed) {
            throw new IllegalArgumentException("Unrecognized SM2 private key encoding"
                    + " (expected 32-byte D, PKCS#8 or SEC1 DER): " + malformed.getMessage(), malformed);
        }
    }

    /**
     * 将字节数组调整为指定长度（截断前导零或补零）。
     */
    private static byte[] fixLength(byte[] src, int targetLen) {
        if (src.length == targetLen) {
            return src;
        }
        byte[] dst = new byte[targetLen];
        if (src.length > targetLen) {
            // BigInteger.toByteArray() 可能多一个前导零，截断
            System.arraycopy(src, src.length - targetLen, dst, 0, targetLen);
        } else {
            // 补前导零
            System.arraycopy(src, 0, dst, targetLen - src.length, src.length);
        }
        return dst;
    }
}
