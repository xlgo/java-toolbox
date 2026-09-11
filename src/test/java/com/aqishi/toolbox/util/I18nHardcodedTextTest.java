package com.aqishi.toolbox.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 硬编码中文文案的<em>棘轮</em>门禁：只许减少，不许增加。
 *
 * <p>存量中文文案有五千多行，一次性迁完不现实；但新功能还在持续引入新的硬编码文案，
 * 抽取速度追不上产出速度。这个测试把每个文件当前的硬编码条数冻结成基线，
 * 新增即失败——先止血，存量再按批迁移。</p>
 *
 * <p>基线要求<em>精确匹配</em>而非只卡上界：迁移使某文件条数下降时测试同样失败，
 * 提示更新基线。这让基线始终反映真实进度，diff 里能直接看出这次迁走了多少。
 * 更新方式（迁移完成后执行一次即可）：</p>
 *
 * <pre>mvn "-Dtest=I18nHardcodedTextTest" "-Di18n.baseline.write=true" test</pre>
 *
 * <p>（引号是为 PowerShell 准备的，不加会被拆成两个参数；bash 下加不加都可以。）</p>
 *
 * <p>注意本门禁统计的是<em>字符串字面量</em>中的汉字，注释里的中文不计——
 * 注释不面向用户，无需国际化。判定逻辑见 {@link JavaStringLiterals}。</p>
 */
class I18nHardcodedTextTest {

    private static final Path SOURCE_ROOT = Paths.get("src", "main", "java");
    private static final Path BASELINE_FILE =
            Paths.get("src", "test", "resources", "i18n", "hardcoded-baseline.txt");
    private static final String WRITE_FLAG = "i18n.baseline.write";

    /**
     * 中文<em>语料</em>文件：其中的汉字是业务数据而非界面文案，不属于国际化目标。
     *
     * <p>这些文件仍然受棘轮约束（条数必须与基线一致，防止夹带真文案进来），
     * 只是不计入"待迁移"总数——否则指标会被语料严重高估。
     * 新增条目必须在此注明理由。</p>
     */
    private static final Map<String, String> DATA_CORPUS_FILES = Map.of(
            "com/aqishi/toolbox/feature/generation/ui/RandomNumberPanel.java",
            "随机测试数据生成语料：百家姓、名字用字、城市、路名、身份证地区码等，"
                    + "翻译后即失去生成中文假数据的意义");

    @Test
    @DisplayName("硬编码中文文案不得超过基线（只许减少）")
    void hardcodedChineseTextDoesNotGrowBeyondBaseline() throws IOException {
        assertTrue(Files.isDirectory(SOURCE_ROOT),
                "找不到源码目录 " + SOURCE_ROOT.toAbsolutePath()
                        + "；本测试需要以项目根目录为工作目录运行");

        Map<String, Integer> actual = countHardcodedTextPerFile();

        if (Boolean.getBoolean(WRITE_FLAG)) {
            writeBaseline(actual);
            return;
        }

        Map<String, Integer> baseline = readBaseline();
        List<String> regressions = new ArrayList<>();
        List<String> improvements = new ArrayList<>();

        Set<String> allPaths = new TreeSet<>(baseline.keySet());
        allPaths.addAll(actual.keySet());
        for (String path : allPaths) {
            int was = baseline.getOrDefault(path, 0);
            int now = actual.getOrDefault(path, 0);
            if (now > was) {
                regressions.add("  " + path + "：" + was + " -> " + now
                        + (was == 0 ? "（新文件引入硬编码中文）" : ""));
            } else if (now < was) {
                improvements.add("  " + path + "：" + was + " -> " + now);
            }
        }

        if (!regressions.isEmpty()) {
            fail(buildRegressionMessage(regressions, improvements));
        }
        if (!improvements.isEmpty()) {
            fail(buildStaleBaselineMessage(improvements, actual));
        }
    }

    @Test
    @DisplayName("数据语料豁免名单不得残留已消失或已清空的文件")
    void dataCorpusExemptionsStayCurrent() throws IOException {
        Map<String, Integer> actual = countHardcodedTextPerFile();
        for (Map.Entry<String, String> exemption : DATA_CORPUS_FILES.entrySet()) {
            String path = exemption.getKey();
            assertTrue(Files.exists(SOURCE_ROOT.resolve(path)),
                    "语料豁免指向了不存在的文件，请从 DATA_CORPUS_FILES 移除：" + path);
            assertTrue(actual.containsKey(path),
                    "文件已不含中文字面量，语料豁免失去意义，请从 DATA_CORPUS_FILES 移除：" + path);
        }
    }

    private static String buildRegressionMessage(List<String> regressions,
                                                 List<String> improvements) {
        StringBuilder message = new StringBuilder();
        message.append("检测到新增的硬编码中文文案（共 ").append(regressions.size())
                .append(" 个文件）。面向用户的文案请改走 I18n.get(key)，")
                .append("并在 messages.properties / messages_zh_CN.properties / ")
                .append("messages_en_US.properties 三份资源中补齐同名键：\n");
        regressions.forEach(line -> message.append(line).append('\n'));
        if (!improvements.isEmpty()) {
            message.append("（同时有文件低于基线，修掉新增后请按提示更新基线）\n");
        }
        message.append("若确属不面向用户的文本（日志、协议常量、SQL 等），")
                .append("可提取为常量或改用英文，再更新基线。");
        return message.toString();
    }

    private static String buildStaleBaselineMessage(List<String> improvements,
                                                    Map<String, Integer> actual) {
        StringBuilder message = new StringBuilder();
        message.append("硬编码中文文案已减少，基线需要同步收紧（共 ")
                .append(improvements.size()).append(" 个文件，当前")
                .append(summarize(actual)).append("）：\n");
        improvements.forEach(line -> message.append(line).append('\n'));
        message.append("执行以下命令更新基线后提交：\n")
                .append("  mvn \"-Dtest=I18nHardcodedTextTest\" \"-D").append(WRITE_FLAG)
                .append("=true\" test");
        return message.toString();
    }

    private static Map<String, Integer> countHardcodedTextPerFile() throws IOException {
        Map<String, Integer> counts = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            List<Path> javaFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .toList();
            for (Path file : javaFiles) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                long count = JavaStringLiterals.scan(source).stream()
                        .filter(JavaStringLiterals::containsHan)
                        .count();
                if (count > 0) {
                    counts.put(toRelativeKey(file), (int) count);
                }
            }
        }
        return counts;
    }

    /** 统一用 '/' 作为分隔符，使基线文件在 Windows 与 Linux CI 上一致。 */
    private static String toRelativeKey(Path file) {
        return SOURCE_ROOT.relativize(file).toString().replace('\\', '/');
    }

    private static Map<String, Integer> readBaseline() throws IOException {
        if (!Files.exists(BASELINE_FILE)) {
            fail("缺少基线文件 " + BASELINE_FILE + "；首次生成："
                    + "mvn \"-Dtest=I18nHardcodedTextTest\" \"-D" + WRITE_FLAG + "=true\" test");
        }
        Map<String, Integer> baseline = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(BASELINE_FILE, StandardCharsets.UTF_8)) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int separator = line.lastIndexOf('=');
            if (separator < 0) {
                fail("基线文件格式错误，期望 `path=count`，实际： " + rawLine);
            }
            baseline.put(line.substring(0, separator).trim(),
                    Integer.parseInt(line.substring(separator + 1).trim()));
        }
        return baseline;
    }

    /** 把统计拆成"待迁移"与"数据语料"两个口径，避免语料把指标撑大。 */
    private static String summarize(Map<String, Integer> counts) {
        int corpus = counts.entrySet().stream()
                .filter(entry -> DATA_CORPUS_FILES.containsKey(entry.getKey()))
                .mapToInt(Map.Entry::getValue).sum();
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        String summary = "待迁移 " + (total - corpus) + " 条";
        return corpus == 0 ? summary : summary + "，另有数据语料 " + corpus + " 条（不需要国际化）";
    }

    private static void writeBaseline(Map<String, Integer> counts) throws IOException {
        StringBuilder content = new StringBuilder();
        content.append("# 硬编码中文文案基线——由 I18nHardcodedTextTest 生成，请勿手工编辑。\n")
                .append("# 重新生成（注意引号，PowerShell 下不加会被拆参）：\n")
                .append("#   mvn \"-Dtest=I18nHardcodedTextTest\" \"-D")
                .append(WRITE_FLAG).append("=true\" test\n")
                .append("# 这些数字只应随国际化推进而下降。\n")
                .append("# 当前：").append(counts.size()).append(" 个文件，")
                .append(summarize(counts)).append("。\n");
        counts.forEach((path, count) -> content.append(path).append('=').append(count).append('\n'));

        Files.createDirectories(BASELINE_FILE.getParent());
        Files.writeString(BASELINE_FILE, content.toString(), StandardCharsets.UTF_8);
        System.out.println("[i18n] 已更新基线 " + BASELINE_FILE + "：" + counts.size()
                + " 个文件，" + summarize(counts) + "。");
    }
}
