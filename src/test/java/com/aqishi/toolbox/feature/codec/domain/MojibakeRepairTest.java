package com.aqishi.toolbox.feature.codec.domain;

import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MojibakeRepairTest {

    private static final Charset GBK = Charset.forName("GBK");
    private static final Charset CP1252 = Charset.forName("windows-1252");

    @Test
    void repairsUtf8ReadAsLatin1() {
        String garbled = new String("中文".getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        MojibakeRepair.Report report = MojibakeRepair.repair(garbled);
        MojibakeRepair.Candidate best = report.candidates().get(0);
        assertEquals("中文", best.text());
        assertEquals(List.of(new MojibakeRepair.Step("windows-1252", "UTF-8")), best.chain());
        assertFalse(report.irreversible());
    }

    @Test
    void repairsUtf8ReadAsWindows1252() {
        // 「文」的 UTF-8 第二字节 0x96 在 1252 里是破折号「–」，典型的「ä¸­æ–‡」
        String garbled = new String("中文测试".getBytes(StandardCharsets.UTF_8), CP1252);
        assertTrue(garbled.indexOf('–') >= 0);
        assertEquals("中文测试", MojibakeRepair.repair(garbled).candidates().get(0).text());
    }

    @Test
    void repairsUtf8ReadAsGbk() {
        String original = "中文测试";
        String garbled = new String(original.getBytes(StandardCharsets.UTF_8), GBK);
        MojibakeRepair.Candidate best = MojibakeRepair.repair(garbled).candidates().get(0);
        assertEquals(original, best.text());
        assertEquals(List.of(new MojibakeRepair.Step("GBK", "UTF-8")), best.chain());
    }

    @Test
    void repairsGbkReadAsLatin1() {
        String garbled = new String("你好世界".getBytes(GBK), StandardCharsets.ISO_8859_1);
        MojibakeRepair.Candidate best = MojibakeRepair.repair(garbled).candidates().get(0);
        assertEquals("你好世界", best.text());
        assertEquals(List.of(new MojibakeRepair.Step("windows-1252", "GBK")), best.chain());
    }

    @Test
    void repairsTwoLevelNesting() {
        String original = "中文测试";
        // 第一次：UTF-8 被当 GBK 读；第二次：这段乱码存成 UTF-8 后又被当 Latin-1 读
        String once = new String(original.getBytes(StandardCharsets.UTF_8), GBK);
        String twice = new String(once.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
        MojibakeRepair.Candidate best = MojibakeRepair.repair(twice).candidates().get(0);
        assertEquals(original, best.text());
        assertEquals(List.of(new MojibakeRepair.Step("windows-1252", "UTF-8"),
                new MojibakeRepair.Step("GBK", "UTF-8")), best.chain());
    }

    @Test
    void flagsKaoMarkersAsIrreversible() {
        // GBK 字节被当 UTF-8 读（非法序列变成 U+FFFD），再存成 UTF-8、又按 GBK 读：锟斤拷
        String replaced = new String("中文".getBytes(GBK), StandardCharsets.UTF_8);
        String kao = new String(replaced.getBytes(StandardCharsets.UTF_8), GBK);
        assertTrue(kao.contains("锟斤拷"));
        MojibakeRepair.Report report = MojibakeRepair.repair(kao);
        assertTrue(report.irreversible());
        assertTrue(report.kaoMarkers() > 0);
        for (MojibakeRepair.Candidate candidate : report.candidates()) {
            assertFalse(candidate.text().contains("中文"), "original bytes are lost");
        }

        MojibakeRepair.Report fffd = MojibakeRepair.repair(replaced);
        assertTrue(fffd.irreversible());
        assertEquals(replaced.chars().filter(c -> c == 0xFFFD).count(), fffd.replacementChars());
    }

    @Test
    void normalTextYieldsNoCandidates() {
        assertTrue(MojibakeRepair.repair("这是一段完全正常的中文。").candidates().isEmpty());
        assertTrue(MojibakeRepair.repair("plain ascii text").candidates().isEmpty());
        assertTrue(MojibakeRepair.repair("Café résumé").candidates().isEmpty());
    }

    @Test
    void candidatesAreRankedByScore() {
        String garbled = new String("编码转换工具".getBytes(StandardCharsets.UTF_8), CP1252);
        List<MojibakeRepair.Candidate> candidates = MojibakeRepair.repair(garbled).candidates();
        for (int i = 1; i < candidates.size(); i++) {
            assertTrue(candidates.get(i - 1).score() >= candidates.get(i).score());
        }
    }
}
