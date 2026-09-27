package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 一个 class 文件的结构化摘要（不可变）。由 {@link ClassFileParser} 生成。
 *
 * <p>只保留排查问题用得上的信息：版本、访问标志、继承关系、字段/方法签名、若干关键属性，
 * 不解析字节码本身。类名一律为点分形式（{@code com.foo.Bar$Inner}）。</p>
 *
 * @param minorVersion        次版本号；{@code 0xFFFF} 表示使用了预览特性
 * @param majorVersion        主版本号（45 = Java 1.1 … 52 = 8 … 65 = 21）
 * @param constantPoolCount   常量池计数（class 文件里的原值，即条目数 + 1）
 * @param accessFlags         类的访问标志原值
 * @param className           本类全限定名；module-info 为 {@code module-info}
 * @param superClassName      父类；{@code java.lang.Object} 与 module-info 为 {@code null}
 * @param signature           类级 Signature 属性（泛型签名原文），没有则为 {@code null}
 * @param recordComponents    record 组件；不是 record 时为 {@code null}
 * @param permittedSubclasses PermittedSubclasses 属性（sealed）；没有该属性时为 {@code null}
 * @param nestHost            NestHost 属性（嵌套类所属的外部类），没有则为 {@code null}
 * @param module              Module 属性（仅 module-info），没有则为 {@code null}
 * @param attributeNames      类级属性名，按出现顺序
 * @param referencedClasses   常量池里引用到的其他类（数组取元素类型），排序去重
 */
public record ClassFileInfo(
        int minorVersion,
        int majorVersion,
        int constantPoolCount,
        int accessFlags,
        String className,
        String superClassName,
        List<String> interfaces,
        List<Member> fields,
        List<Member> methods,
        String sourceFile,
        String signature,
        boolean deprecated,
        List<String> annotations,
        List<Member> recordComponents,
        List<String> permittedSubclasses,
        String nestHost,
        List<String> nestMembers,
        ModuleInfo module,
        List<String> attributeNames,
        List<String> referencedClasses) {

    /** 次版本号为此值时表示依赖某一特定版本的预览特性（JEP 12，Java 12+）。 */
    public static final int PREVIEW_MINOR = 0xFFFF;

    public static final int ACC_PUBLIC = 0x0001;
    public static final int ACC_PRIVATE = 0x0002;
    public static final int ACC_PROTECTED = 0x0004;
    public static final int ACC_STATIC = 0x0008;
    public static final int ACC_FINAL = 0x0010;
    public static final int ACC_SUPER = 0x0020;
    public static final int ACC_SYNCHRONIZED = 0x0020;
    public static final int ACC_VOLATILE = 0x0040;
    public static final int ACC_BRIDGE = 0x0040;
    public static final int ACC_TRANSIENT = 0x0080;
    public static final int ACC_VARARGS = 0x0080;
    public static final int ACC_NATIVE = 0x0100;
    public static final int ACC_INTERFACE = 0x0200;
    public static final int ACC_ABSTRACT = 0x0400;
    public static final int ACC_STRICT = 0x0800;
    public static final int ACC_SYNTHETIC = 0x1000;
    public static final int ACC_ANNOTATION = 0x2000;
    public static final int ACC_ENUM = 0x4000;
    public static final int ACC_MODULE = 0x8000;

    public ClassFileInfo {
        interfaces = List.copyOf(interfaces);
        fields = List.copyOf(fields);
        methods = List.copyOf(methods);
        annotations = List.copyOf(annotations);
        recordComponents = recordComponents == null ? null : List.copyOf(recordComponents);
        permittedSubclasses = permittedSubclasses == null ? null : List.copyOf(permittedSubclasses);
        nestMembers = List.copyOf(nestMembers);
        attributeNames = List.copyOf(attributeNames);
        referencedClasses = List.copyOf(referencedClasses);
    }

    /** 字段、方法或 record 组件。 */
    public record Member(int accessFlags, String name, String descriptor, String signature, boolean deprecated) {

        /** 字段类型或方法签名的 Java 风格文本，如 {@code void (int, String)}。 */
        public String renderedType() {
            return descriptor.startsWith("(") ? JvmDescriptors.method(descriptor)
                    : JvmDescriptors.fieldType(descriptor);
        }
    }

    /** module-info 的 Module 属性。包名、模块名均为点分形式。 */
    public record ModuleInfo(String name, int flags, String version, List<Requires> requires,
                             List<Exports> exports, List<Exports> opens, List<String> uses,
                             List<Provides> provides, String mainClass) {

        public static final int ACC_OPEN = 0x0020;

        public ModuleInfo {
            requires = List.copyOf(requires);
            exports = List.copyOf(exports);
            opens = List.copyOf(opens);
            uses = List.copyOf(uses);
            provides = List.copyOf(provides);
        }

        public boolean open() {
            return (flags & ACC_OPEN) != 0;
        }
    }

    /** requires 条目；{@code flags} 取 transitive(0x20) / static(0x40) 等。 */
    public record Requires(String module, int flags, String version) {
        public static final int ACC_TRANSITIVE = 0x0020;
        public static final int ACC_STATIC_PHASE = 0x0040;

        public boolean transitive() {
            return (flags & ACC_TRANSITIVE) != 0;
        }

        public boolean staticPhase() {
            return (flags & ACC_STATIC_PHASE) != 0;
        }
    }

    /** exports / opens 条目；{@code targets} 为空表示对所有模块导出。 */
    public record Exports(String packageName, List<String> targets) {
        public Exports {
            targets = List.copyOf(targets);
        }
    }

    /** provides 条目。 */
    public record Provides(String service, List<String> implementations) {
        public Provides {
            implementations = List.copyOf(implementations);
        }
    }

    /** 类的形态，按“最具体”优先判定。 */
    public enum Kind { CLASS, INTERFACE, ENUM, ANNOTATION, RECORD, MODULE }

    public Kind kind() {
        if ((accessFlags & ACC_MODULE) != 0) {
            return Kind.MODULE;
        }
        if ((accessFlags & ACC_ANNOTATION) != 0) {
            return Kind.ANNOTATION;
        }
        if ((accessFlags & ACC_INTERFACE) != 0) {
            return Kind.INTERFACE;
        }
        if ((accessFlags & ACC_ENUM) != 0) {
            return Kind.ENUM;
        }
        if (recordComponents != null) {
            return Kind.RECORD;
        }
        return Kind.CLASS;
    }

    public boolean preview() {
        return minorVersion == PREVIEW_MINOR;
    }

    /** sealed 类/接口：带 PermittedSubclasses 属性。 */
    public boolean sealed() {
        return permittedSubclasses != null;
    }

    /** 运行所需的最低 Java 特性版本号（1.x 记为 x）；见 {@link #featureRelease(int)}。 */
    public int featureRelease() {
        return featureRelease(majorVersion);
    }

    /** 面向用户的 Java 版本名，如 {@code 1.4}、{@code 8}、{@code 21}。 */
    public String javaRelease() {
        return releaseName(majorVersion);
    }

    /** {@code 65.0}、{@code 65.65535} 这类 major.minor 文本。 */
    public String versionText() {
        return majorVersion + "." + minorVersion;
    }

    /** 访问标志对应的关键字（类语境）。 */
    public List<String> accessNames() {
        return classAccessNames(accessFlags);
    }

    /**
     * 主版本号 → Java 特性版本号。
     *
     * <p>从 49（Java 5）起严格满足 {@code release = major - 44}：49→5、52→8、61→17、65→21、69→25。
     * 更早的版本沿用同一公式得到 1.x 中的 x：46→1.2、47→1.3、48→1.4。</p>
     *
     * <p>45 比较特殊：JDK 1.0.2 与 1.1 产出的都是 45.x（1.0.2 为 45.3，1.1 起 minor 可到 45.65535），
     * 单凭 major 无法区分两者；这里统一记为 1.1——两者的运行时要求本来就一样。
     * 小于 45 的主版本不存在，返回 0。</p>
     */
    public static int featureRelease(int major) {
        return major < 45 ? 0 : major - 44;
    }

    /** 主版本号 → 面向用户的版本名：{@code 1.1}…{@code 1.4}、{@code 5}…{@code 25}；非法值为 {@code ?}。 */
    public static String releaseName(int major) {
        int feature = featureRelease(major);
        if (feature <= 0) {
            return "?";
        }
        return feature <= 4 ? "1." + feature : String.valueOf(feature);
    }

    /** 类语境下的访问标志关键字。0x0020 在类上是历史遗留的 ACC_SUPER，不展示。 */
    public static List<String> classAccessNames(int flags) {
        List<String> names = new ArrayList<>();
        add(names, flags, ACC_PUBLIC, "public");
        add(names, flags, ACC_FINAL, "final");
        add(names, flags, ACC_ABSTRACT, "abstract");
        add(names, flags, ACC_INTERFACE, "interface");
        add(names, flags, ACC_ANNOTATION, "annotation");
        add(names, flags, ACC_ENUM, "enum");
        add(names, flags, ACC_MODULE, "module");
        add(names, flags, ACC_SYNTHETIC, "synthetic");
        return names;
    }

    /** 字段语境下的访问标志关键字。 */
    public static List<String> fieldAccessNames(int flags) {
        List<String> names = new ArrayList<>();
        add(names, flags, ACC_PUBLIC, "public");
        add(names, flags, ACC_PRIVATE, "private");
        add(names, flags, ACC_PROTECTED, "protected");
        add(names, flags, ACC_STATIC, "static");
        add(names, flags, ACC_FINAL, "final");
        add(names, flags, ACC_VOLATILE, "volatile");
        add(names, flags, ACC_TRANSIENT, "transient");
        add(names, flags, ACC_SYNTHETIC, "synthetic");
        add(names, flags, ACC_ENUM, "enum");
        return names;
    }

    /** 方法语境下的访问标志关键字（0x0040 为 bridge、0x0080 为 varargs）。 */
    public static List<String> methodAccessNames(int flags) {
        List<String> names = new ArrayList<>();
        add(names, flags, ACC_PUBLIC, "public");
        add(names, flags, ACC_PRIVATE, "private");
        add(names, flags, ACC_PROTECTED, "protected");
        add(names, flags, ACC_STATIC, "static");
        add(names, flags, ACC_FINAL, "final");
        add(names, flags, ACC_SYNCHRONIZED, "synchronized");
        add(names, flags, ACC_BRIDGE, "bridge");
        add(names, flags, ACC_VARARGS, "varargs");
        add(names, flags, ACC_NATIVE, "native");
        add(names, flags, ACC_ABSTRACT, "abstract");
        add(names, flags, ACC_STRICT, "strictfp");
        add(names, flags, ACC_SYNTHETIC, "synthetic");
        return names;
    }

    private static void add(List<String> names, int flags, int bit, String name) {
        if ((flags & bit) != 0) {
            names.add(name);
        }
    }
}
