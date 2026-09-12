package com.aqishi.toolbox.feature.cloud.ui;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLSocketFactory;

/**
 * 当前 K8s 集群连接的只读上下文。
 *
 * <p>从 {@link K8sManagerPanel} 拆出的 YAML 查看、日志查看、容器控制台与文件传输对话框
 * 通过该接口读取连接参数，而不再反向持有面板实例，便于独立演进。</p>
 */
interface K8sClusterContext {

    /** API Server 地址（含协议）。 */
    String serverUrl();

    /** Bearer Token，可能为空。 */
    String token();

    /** 是否跳过 TLS 校验。 */
    boolean skipTls();

    /** 集群 CA（PEM），可能为空。 */
    String caCert();

    /** 客户端证书（PEM），可能为空。 */
    String clientCert();

    /** 客户端私钥（PEM），可能为空。 */
    String clientKey();

    /**
     * 当前连接的 TLS 套接字工厂。
     *
     * <p>启动连接后由 {@code K8sManagerPanel} 缓存；尚未建立时按当前 TLS 选项惰性构建，
     * 因此对 https 端点始终返回非空工厂。</p>
     */
    SSLSocketFactory socketFactory();

    /** 已建立的主机名校验器；未连接时为 {@code null}。 */
    HostnameVerifier hostnameVerifier();

    /** 发送非流式 API 请求。 */
    String request(String method, String apiPath, String body) throws Exception;

    /** 需随应用关闭一并取消的传输资源登记表。 */
    TransferRegistry transfers();
}
