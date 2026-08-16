package com.aqishi.toolbox.catalog;

import com.aqishi.toolbox.algo.HanoiPanel;
import com.aqishi.toolbox.algo.PinGamePanel;
import com.aqishi.toolbox.algo.SearchPanel;
import com.aqishi.toolbox.algo.SortPanel;
import com.aqishi.toolbox.calc.CalculatorPanel;
import com.aqishi.toolbox.calc.ChmodPanel;
import com.aqishi.toolbox.calc.StatisticsPanel;
import com.aqishi.toolbox.convert.Base64ImagePanel;
import com.aqishi.toolbox.convert.ConvertPanel;
import com.aqishi.toolbox.convert.FormatConvertPanel;
import com.aqishi.toolbox.convert.TimePanel;
import com.aqishi.toolbox.convert.UrlToolPanel;
import com.aqishi.toolbox.crypto.AsymmetricPanel;
import com.aqishi.toolbox.crypto.CryptoPanel;
import com.aqishi.toolbox.crypto.SymmetricPanel;
import com.aqishi.toolbox.misc.AccountManagerPanel;
import com.aqishi.toolbox.misc.BpmnPanel;
import com.aqishi.toolbox.misc.CallbackTestPanel;
import com.aqishi.toolbox.misc.CertPanel;
import com.aqishi.toolbox.misc.ColorPanel;
import com.aqishi.toolbox.misc.CronPanel;
import com.aqishi.toolbox.misc.DatabasePanel;
import com.aqishi.toolbox.misc.DockerComposePanel;
import com.aqishi.toolbox.misc.FlowchartPanel;
import com.aqishi.toolbox.misc.HostsManagerPanel;
import com.aqishi.toolbox.misc.HttpTestPanel;
import com.aqishi.toolbox.misc.JsonPanel;
import com.aqishi.toolbox.misc.JwtPanel;
import com.aqishi.toolbox.misc.K8sManagerPanel;
import com.aqishi.toolbox.misc.K8sPanel;
import com.aqishi.toolbox.misc.KafkaPanel;
import com.aqishi.toolbox.misc.MermaidPanel;
import com.aqishi.toolbox.misc.DataGeneratorPanel;
import com.aqishi.toolbox.misc.MqttClientPanel;
import com.aqishi.toolbox.misc.PortScannerPanel;
import com.aqishi.toolbox.misc.QrCodePanel;
import com.aqishi.toolbox.misc.RedisPanel;
import com.aqishi.toolbox.misc.RegexPanel;
import com.aqishi.toolbox.misc.SqlPanel;
import com.aqishi.toolbox.misc.SshClientPanel;
import com.aqishi.toolbox.misc.StringToolPanel;
import com.aqishi.toolbox.misc.SubnetPanel;
import com.aqishi.toolbox.misc.TextDiffPanel;
import com.aqishi.toolbox.misc.TotpPanel;
import com.aqishi.toolbox.misc.WeChatPanel;
import com.aqishi.toolbox.misc.WebSocketClientPanel;
import com.aqishi.toolbox.misc.XmlPanel;
import com.aqishi.toolbox.misc.ZooKeeperPanel;
import com.aqishi.toolbox.monitor.RemoteDesktopPanel;
import com.aqishi.toolbox.monitor.VideoMonitorPanel;
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
        factories.put("http.client", context -> new HttpTestPanel());
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
        factories.put("k8s.manager", context -> new K8sManagerPanel());
        factories.put("chmod.calc", context -> new ChmodPanel());
        factories.put("cron.parser", context -> new CronPanel());
        factories.put("hosts.manager", context -> new HostsManagerPanel());
        factories.put("wechat.sender", context -> new WeChatPanel());
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
