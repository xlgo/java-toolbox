package com.aqishi.toolbox.catalog;

import com.aqishi.toolbox.feature.compute.ui.HanoiPanel;
import com.aqishi.toolbox.feature.compute.ui.PinGamePanel;
import com.aqishi.toolbox.feature.compute.ui.SearchPanel;
import com.aqishi.toolbox.feature.compute.ui.SortPanel;
import com.aqishi.toolbox.feature.compute.ui.CalculatorPanel;
import com.aqishi.toolbox.feature.system.ui.ChmodPanel;
import com.aqishi.toolbox.feature.compute.ui.StatisticsPanel;
import com.aqishi.toolbox.feature.codec.ui.Base64ImagePanel;
import com.aqishi.toolbox.feature.codec.ui.ConvertPanel;
import com.aqishi.toolbox.feature.codec.ui.FormatConvertPanel;
import com.aqishi.toolbox.feature.codec.ui.TimePanel;
import com.aqishi.toolbox.feature.codec.ui.UrlToolPanel;
import com.aqishi.toolbox.feature.security.ui.AsymmetricPanel;
import com.aqishi.toolbox.feature.security.ui.CryptoPanel;
import com.aqishi.toolbox.feature.security.ui.SymmetricPanel;
import com.aqishi.toolbox.feature.security.ui.AccountManagerPanel;
import com.aqishi.toolbox.feature.diagram.ui.BpmnPanel;
import com.aqishi.toolbox.feature.network.ui.CallbackTestPanel;
import com.aqishi.toolbox.feature.network.ui.OpenApiPanel;
import com.aqishi.toolbox.feature.security.ui.BatchDigestPanel;
import com.aqishi.toolbox.feature.security.ui.CertInspectorPanel;
import com.aqishi.toolbox.feature.security.ui.CertPanel;
import com.aqishi.toolbox.feature.generation.ui.ColorPanel;
import com.aqishi.toolbox.feature.system.ui.CronPanel;
import com.aqishi.toolbox.feature.data.ui.DatabasePanel;
import com.aqishi.toolbox.feature.cloud.ui.DockerComposePanel;
import com.aqishi.toolbox.feature.diagram.ui.FlowchartPanel;
import com.aqishi.toolbox.feature.system.ui.HostsManagerPanel;
import com.aqishi.toolbox.feature.system.ui.LogViewerPanel;
import com.aqishi.toolbox.feature.network.ui.HttpTestPanel;
import com.aqishi.toolbox.feature.codec.ui.JsonPanel;
import com.aqishi.toolbox.feature.codec.ui.JsonPathPanel;
import com.aqishi.toolbox.feature.security.ui.JwtPanel;
import com.aqishi.toolbox.feature.cloud.ui.K8sManagerPanel;
import com.aqishi.toolbox.feature.cloud.ui.K8sPanel;
import com.aqishi.toolbox.feature.data.ui.KafkaPanel;
import com.aqishi.toolbox.feature.diagram.ui.MermaidPanel;
import com.aqishi.toolbox.feature.generation.ui.DataGeneratorPanel;
import com.aqishi.toolbox.feature.network.ui.MqttClientPanel;
import com.aqishi.toolbox.feature.network.ui.PortScannerPanel;
import com.aqishi.toolbox.feature.generation.ui.QrCodePanel;
import com.aqishi.toolbox.feature.data.ui.RedisPanel;
import com.aqishi.toolbox.feature.codec.ui.RegexPanel;
import com.aqishi.toolbox.feature.codec.ui.SqlPanel;
import com.aqishi.toolbox.feature.network.ui.SshClientPanel;
import com.aqishi.toolbox.feature.codec.ui.StringToolPanel;
import com.aqishi.toolbox.feature.network.ui.SubnetPanel;
import com.aqishi.toolbox.feature.codec.ui.TextDiffPanel;
import com.aqishi.toolbox.feature.security.ui.TotpPanel;
import com.aqishi.toolbox.feature.system.ui.WeChatPanel;
import com.aqishi.toolbox.feature.network.ui.WebSocketClientPanel;
import com.aqishi.toolbox.feature.codec.ui.XmlPanel;
import com.aqishi.toolbox.feature.data.ui.ZooKeeperPanel;
import com.aqishi.toolbox.feature.monitor.ui.RemoteDesktopPanel;
import com.aqishi.toolbox.feature.monitor.ui.VideoMonitorPanel;
import com.aqishi.toolbox.ui.ToolPanel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * 集中创建和索引所有工具。注册表是 MainFrame 与具体面板之间唯一的装配边界。
 */
public final class ToolRegistry {

    private final List<ToolCategory> categories;
    private final List<ToolDescriptor> descriptors;
    private final Map<String, ToolDescriptor> descriptorsById;
    private final Map<String, Function<ToolboxContext, ToolPanel>> factoriesById;

    private ToolRegistry(List<ToolCategory> categories,
                         List<ToolDescriptor> descriptors,
                         Map<String, Function<ToolboxContext, ToolPanel>> factoriesById) {
        this.categories = Collections.unmodifiableList(new ArrayList<>(categories));
        this.descriptors = Collections.unmodifiableList(new ArrayList<>(descriptors));
        LinkedHashMap<String, ToolDescriptor> index = new LinkedHashMap<>();
        for (ToolDescriptor descriptor : descriptors) {
            if (index.put(descriptor.getId(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate tool id: " + descriptor.getId());
            }
        }
        this.descriptorsById = Collections.unmodifiableMap(index);
        this.factoriesById = Collections.unmodifiableMap(new LinkedHashMap<>(factoriesById));
    }

    public static ToolRegistry createDefault() {
        LinkedHashMap<String, Function<ToolboxContext, ToolPanel>> factories = new LinkedHashMap<>();
        factories.put("hash.codec", context -> new CryptoPanel());
        factories.put("symmetric.crypto", context -> new SymmetricPanel());
        factories.put("asymmetric.crypto", context -> new AsymmetricPanel());
        factories.put("cert.management", context -> new CertPanel());
        factories.put("account.manager", context -> new AccountManagerPanel(context.getVaultService(), context.getSecureClipboard()));
        factories.put("totp.authenticator", context -> new TotpPanel(context.getVaultService(), context.getSecureClipboard()));
        factories.put("jwt.codec", context -> new JwtPanel());
        factories.put("file.batch.digest", context -> new BatchDigestPanel());
        factories.put("cert.inspector", context -> new CertInspectorPanel());
        factories.put("radix.encoding", context -> new ConvertPanel());
        factories.put("timestamp", context -> new TimePanel());
        factories.put("base64.image", context -> new Base64ImagePanel());
        factories.put("url.tool", context -> new UrlToolPanel());
        factories.put("format.convert", context -> new FormatConvertPanel());
        factories.put("json.format", context -> new JsonPanel());
        factories.put("xml.format", context -> new XmlPanel());
        factories.put("sql.format", context -> new SqlPanel());
        factories.put("string.tool", context -> new StringToolPanel());
        factories.put("regex.tester", context -> new RegexPanel());
        factories.put("text.diff", context -> new TextDiffPanel());
        factories.put("jsonpath.tester", context -> new JsonPathPanel());
        factories.put("http.client", context -> new HttpTestPanel());
        factories.put("openapi.workbench", context -> new OpenApiPanel());
        factories.put("callback.mock", context -> new CallbackTestPanel());
        factories.put("websocket.client", context -> new WebSocketClientPanel());
        factories.put("mqtt.client", context -> new MqttClientPanel());
        factories.put("subnet.calc", context -> new SubnetPanel());
        factories.put("port.scanner", context -> new PortScannerPanel());
        factories.put("ssh", context -> new SshClientPanel());
        factories.put("database.connector", context -> new DatabasePanel());
        factories.put("redis.management", context -> new RedisPanel());
        factories.put("kafka.connector", context -> new KafkaPanel());
        factories.put("zookeeper.management", context -> new ZooKeeperPanel());
        factories.put("docker.convert", context -> new DockerComposePanel());
        factories.put("k8s.deployment", context -> new K8sPanel());
        factories.put("k8s.manager", context -> new K8sManagerPanel(
                context.getKubernetesServiceFactory()));
        factories.put("chmod.calc", context -> new ChmodPanel());
        factories.put("cron.parser", context -> new CronPanel());
        factories.put("hosts.manager", context -> new HostsManagerPanel());
        factories.put("wechat.sender", context -> new WeChatPanel());
        factories.put("log.viewer", context -> new LogViewerPanel());
        factories.put("data.generator", context -> new DataGeneratorPanel());
        factories.put("qrcode", context -> new QrCodePanel());
        factories.put("color.convert", context -> new ColorPanel());
        factories.put("calculator", context -> new CalculatorPanel());
        factories.put("statistics", context -> new StatisticsPanel());
        factories.put("sort.visualizer", context -> new SortPanel());
        factories.put("search.algorithm", context -> new SearchPanel());
        factories.put("hanoi", context -> new HanoiPanel());
        factories.put("pingame", context -> new PinGamePanel());
        factories.put("bpmn.designer", context -> new BpmnPanel());
        factories.put("mermaid", context -> new MermaidPanel());
        factories.put("flowchart", context -> new FlowchartPanel());
        factories.put("video.monitor", context -> new VideoMonitorPanel());
        factories.put("remote_desktop", context -> new RemoteDesktopPanel());
        return new ToolRegistry(ToolCatalog.categories(), ToolCatalog.descriptors(), factories);
    }

    public List<ToolCategory> getCategories() {
        return categories;
    }

    public List<ToolDescriptor> getDescriptors() {
        return descriptors;
    }

    public ToolDescriptor find(String id) {
        return id == null ? null : descriptorsById.get(id);
    }

    public ToolPanel create(String id, ToolboxContext context) {
        ToolDescriptor descriptor = find(id);
        if (descriptor == null) {
            throw new IllegalArgumentException("Unknown tool id: " + id);
        }
        Function<ToolboxContext, ToolPanel> factory = factoriesById.get(id);
        if (factory == null) {
            throw new IllegalStateException("Missing factory for tool id: " + id);
        }
        ToolPanel panel = Objects.requireNonNull(factory.apply(context), "factory result");
        panel.bindDescriptor(descriptor);
        return panel;
    }

    public List<ToolPanel> createAll(ToolboxContext context) {
        List<ToolPanel> panels = new ArrayList<>();
        for (ToolDescriptor descriptor : descriptors) {
            panels.add(create(descriptor.getId(), context));
        }
        return Collections.unmodifiableList(panels);
    }
}
