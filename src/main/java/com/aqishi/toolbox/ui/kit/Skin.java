package com.aqishi.toolbox.ui.kit;

import com.aqishi.toolbox.util.I18n;

import java.awt.Color;
import java.util.Locale;

/**
 * 皮肤基调：决定启动器、侧边导航与外壳的配色策略。
 *
 * <p>效果图提供三档皮肤——暗黑模式、多彩浅色、护眼模式。三者的差别不在控件本身，
 * 而在卡片与导航的底色如何从分类品牌色推导：暗黑压暗、浅色提亮、护眼向暖黄偏移。
 * 控件颜色仍由 FlatLaf 的 UIManager 提供，因此换肤不会破坏任何工具面板。</p>
 *
 * <p>皮肤与「主题」是两个层次：主题决定 LAF（50 多套可选），皮肤决定壳层的彩色卡片。
 * 三档皮肤各自映射到一个内置主题，其余主题按明暗自动落到 {@link #DARK} 或 {@link #LIGHT}。</p>
 */
public enum Skin {

    LIGHT("skin.light", "Light", 0xF4F5F2),
    DARK("skin.dark", "Dark", 0x15181C),
    MIDNIGHT("skin.midnight", "Midnight", 0x0B1526),
    FOREST("skin.forest", "Forest", 0x0F1A12),
    PAPER("skin.paper", "Paper", 0xF7F0DE),
    EYE_CARE("skin.eye", "Eye Care", 0xFAF6EA);

    private final String labelKey;
    private final String labelEn;
    /** 卡片渐变的融合目标色，用来把品牌色压进当前皮肤的背景层 */
    private final int baseRgb;

    Skin(String labelKey, String labelEn, int baseRgb) {
        this.labelKey = labelKey;
        this.labelEn = labelEn;
        this.baseRgb = baseRgb;
    }

    public String labelKey() {
        return labelKey;
    }

    /** 当前语言下的皮肤名；文案走资源文件，不在枚举里写死中文 */
    public String label() {
        return I18n.get(labelKey);
    }

    public String labelEn() {
        return labelEn;
    }

    /** 是否为深色壳层：决定文字、描边与图标的对比方向 */
    public boolean isDark() {
        return this == DARK || this == MIDNIGHT || this == FOREST;
    }

    /** 暖色皮肤：纸感与护眼都压低蓝色通道 */
    public boolean isWarm() {
        return this == EYE_CARE || this == PAPER;
    }

    public Color base() {
        return new Color(baseRgb);
    }

    /**
     * 分类品牌色。同一个分类共享一个色相，不同分类之间保持足够区分度，
     * 这样启动器上的卡片扫描起来是有结构的，而不是一片随机彩色。
     */
    public static Color seed(String categoryId) {
        String key = categoryId == null ? "" : categoryId.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "security" -> new Color(0x6366F1);
            case "codec" -> new Color(0x0EA5E9);
            case "network" -> new Color(0x10B981);
            case "data" -> new Color(0xF59E0B);
            case "cloud" -> new Color(0x3B82F6);
            case "system" -> new Color(0x64748B);
            case "generation" -> new Color(0xD946EF);
            case "compute" -> new Color(0xEF4444);
            case "diagram" -> new Color(0x8B5CF6);
            case "monitor" -> new Color(0xEC4899);
            default -> new Color(0x386AE8);
        };
    }

    /**
     * 单个工具的种子色：在分类色相上做偏移，让同组磁贴出现邻近色变化。
     *
     * <p>效果图同一屏里是蓝、青、紫、黄交替的，如果整组只用分类色会连成一片。
     * 偏移量只由工具 ID 决定，因此每次渲染稳定，不会闪烁；范围控制在 ±30° 内，
     * 卡片仍能被读作「同一组」，但不会像复制粘贴。</p>
     */
    public static Color seed(String categoryId, String toolId) {
        Color base = seed(categoryId);
        if (toolId == null || toolId.isEmpty()) {
            return base;
        }
        float[] hsb = Color.RGBtoHSB(base.getRed(), base.getGreen(), base.getBlue(), null);
        int hash = Math.abs(toolId.hashCode());
        // -22° ~ +22°：足够看出差异，又不会跑到别的分类的色相上
        float offset = ((hash % 45) - 22) / 360f;
        float hue = (hsb[0] + offset + 1f) % 1f;
        // 亮度 ±3%，避免相邻卡片颜色完全相同
        float brightness = Math.min(1f, Math.max(0.55f, hsb[2] + ((hash / 45) % 7 - 3) / 100f));
        return Color.getHSBColor(hue, hsb[1], brightness);
    }

    /** 卡片渐变起点 */
    public Color tileFrom(Color seed) {
        return tint(seed, 0.80f, 0.86f, 0.70f);
    }

    /** 卡片渐变终点 */
    public Color tileTo(Color seed) {
        return tint(seed, 0.58f, 0.70f, 0.46f);
    }

    /**
     * 把品牌色按皮肤压进底色的混合比例。
     *
     * @param light 浅色皮肤保留的品牌色比例
     * @param dark  暗黑皮肤保留的比例
     * @param warm  护眼皮肤保留的比例
     */
    private Color tint(Color seed, float light, float dark, float warm) {
        return switch (this) {
            case DARK, MIDNIGHT, FOREST -> Tokens.blend(base(), seed, 1f - dark);
            case PAPER, EYE_CARE -> warmTint(seed, warm);
            case LIGHT -> Tokens.blend(Color.WHITE, seed, 1f - light);
        };
    }

    /**
     * 护眼皮肤：把品牌色收敛到暖黄区间并压低饱和度，再向米色底靠。
     *
     * <p>如果只是简单压蓝色通道，得到的仍是饱和的青蓝——看上去刺眼，也不护眼。
     * 这里先把色相拉到 17°~60°（橙到黄），只保留少量分类差异，再叠加米色底。</p>
     */
    private Color warmTint(Color seed, float amount) {
        float[] hsb = Color.RGBtoHSB(seed.getRed(), seed.getGreen(), seed.getBlue(), null);
        float warmHue = 0.035f + hsb[0] * 0.13f;
        float saturation = Math.min(0.34f, 0.16f + hsb[1] * 0.18f);
        float brightness = Math.min(0.98f, hsb[2] + 0.06f);
        Color warmSeed = Color.getHSBColor(warmHue, saturation, brightness);
        Color mixed = Tokens.blend(base(), warmSeed, amount);
        return new Color(
                Math.min(255, mixed.getRed() + 3),
                mixed.getGreen(),
                Math.max(0, mixed.getBlue() - 5));
    }

    /** 图标底块的饱和渐变起点（比卡片更浓，作为视觉锚点） */
    public Color glyphFrom(Color seed) {
        return switch (this) {
            case DARK, MIDNIGHT, FOREST -> Tokens.blend(seed, base(), 0.18f);
            case PAPER, EYE_CARE -> warmGlyph(seed);
            case LIGHT -> seed;
        };
    }

    /** 图标底块的渐变终点 */
    public Color glyphTo(Color seed) {
        return switch (this) {
            case DARK, MIDNIGHT, FOREST -> Tokens.shift(Tokens.blend(seed, base(), 0.24f), -0.18f);
            case PAPER, EYE_CARE -> Tokens.shift(warmGlyph(seed), -0.14f);
            case LIGHT -> Tokens.shift(seed, -0.14f);
        };
    }

    /**
     * 护眼模式的图标色：卡片本身很淡，图标必须明显更深、更暖，
     * 否则整块淡黄糊成一片，认不出是图标。
     */
    private Color warmGlyph(Color seed) {
        float[] hsb = Color.RGBtoHSB(seed.getRed(), seed.getGreen(), seed.getBlue(), null);
        float warmHue = 0.035f + hsb[0] * 0.13f;
        return Color.getHSBColor(warmHue, 0.56f, 0.84f);
    }

    /** 图标字形色：统一用白色，在饱和底色上对比度最高 */
    public Color glyphInk() {
        return Color.WHITE;
    }

    /** 卡片描边：深色皮肤下几乎不可见，浅色皮肤下是一道很淡的色边 */
    public Color tileBorder(Color seed) {
        return switch (this) {
            case DARK, MIDNIGHT, FOREST -> Tokens.blend(base(), seed, 0.18f);
            case PAPER, EYE_CARE -> Tokens.blend(warmTint(seed, 0.40f), base(), 0.30f);
            case LIGHT -> Tokens.blend(seed, Color.WHITE, 0.80f);
        };
    }
}
