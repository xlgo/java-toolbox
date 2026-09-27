package com.aqishi.toolbox.feature.system.domain;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * class 文件解析器（JVMS §4），只读结构，不依赖 ASM。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>所有读取都做边界检查，越界抛 {@link ClassFileException}（{@code TRUNCATED}）并带偏移；
 *       绝不抛 {@code ArrayIndexOutOfBoundsException} 之类让调用方猜原因。</li>
 *   <li>每个计数（常量池、字段、方法、属性、注解）读出后先与剩余字节数比较：每个条目至少占若干字节，
 *       剩余字节放不下就直接判为 {@code LIMIT_EXCEEDED}——防止 65535 个条目的伪造计数驱动大量分配。</li>
 *   <li>属性按声明长度切片解析：已知属性解析完后无论读了多少都跳到声明的末尾，未知属性整体跳过，
 *       因此一个格式怪异的属性不会让后续字段错位。</li>
 *   <li>long / double 常量占两个槽位（JVMS §4.4.5 的历史遗留），第二个槽位不可引用。</li>
 * </ul>
 */
public final class ClassFileParser {

    /** 单个 class 文件允许的最大字节数。真实世界最大的类也只有几 MB。 */
    public static final int MAX_CLASS_BYTES = 32 * 1024 * 1024;
    /** 注解元素值允许的最大嵌套深度，防止恶意输入递归爆栈。 */
    static final int MAX_ANNOTATION_DEPTH = 32;

    public static final int MAGIC = 0xCAFEBABE;

    private static final int TAG_UTF8 = 1;
    private static final int TAG_INTEGER = 3;
    private static final int TAG_FLOAT = 4;
    private static final int TAG_LONG = 5;
    private static final int TAG_DOUBLE = 6;
    private static final int TAG_CLASS = 7;
    private static final int TAG_STRING = 8;
    private static final int TAG_FIELDREF = 9;
    private static final int TAG_METHODREF = 10;
    private static final int TAG_INTERFACE_METHODREF = 11;
    private static final int TAG_NAME_AND_TYPE = 12;
    private static final int TAG_METHOD_HANDLE = 15;
    private static final int TAG_METHOD_TYPE = 16;
    private static final int TAG_DYNAMIC = 17;
    private static final int TAG_INVOKE_DYNAMIC = 18;
    private static final int TAG_MODULE = 19;
    private static final int TAG_PACKAGE = 20;
    /** long / double 之后被占用的第二个槽位。 */
    private static final int TAG_RESERVED = -1;

    /** class 文件头：魔数之后的版本号。只需前 8 个字节。 */
    public record Header(int minorVersion, int majorVersion) {
        public int featureRelease() {
            return ClassFileInfo.featureRelease(majorVersion);
        }

        public boolean preview() {
            return minorVersion == ClassFileInfo.PREVIEW_MINOR;
        }
    }

    /**
     * 只读版本号。扫描整个 JAR 统计版本分布时用它，不必解析常量池。
     *
     * @param data   至少包含前 8 个字节的数据
     * @param length 有效长度
     */
    public static Header parseHeader(byte[] data, int length) throws ClassFileException {
        Reader reader = new Reader(data, 0, Math.min(length, data.length));
        int magic = reader.u4();
        if (magic != MAGIC) {
            throw new ClassFileException(ClassFileException.Code.BAD_MAGIC, 0,
                    "Bad magic 0x" + Integer.toHexString(magic).toUpperCase(java.util.Locale.ROOT));
        }
        int minor = reader.u2();
        int major = reader.u2();
        if (major < 45) {
            throw new ClassFileException(ClassFileException.Code.BAD_VERSION, 6, "Major version " + major + " < 45");
        }
        return new Header(minor, major);
    }

    /** 从流里读取（最多 {@link #MAX_CLASS_BYTES} + 1 字节以判断超限）后解析。 */
    public ClassFileInfo parse(InputStream in) throws IOException {
        byte[] data = in.readNBytes(MAX_CLASS_BYTES + 1);
        return parse(data);
    }

    public ClassFileInfo parse(byte[] data) throws ClassFileException {
        if (data.length > MAX_CLASS_BYTES) {
            throw new ClassFileException(ClassFileException.Code.TOO_LARGE, MAX_CLASS_BYTES,
                    "Class file larger than " + MAX_CLASS_BYTES + " bytes");
        }
        return new Parse(data).run();
    }

    // ==========================================
    // 单次解析的状态
    // ==========================================
    private static final class Parse {
        private final byte[] data;
        private final Reader reader;
        private int[] tags;
        /** 每个常量池条目在 data 中的起始偏移（tag 之后的第一个字节）。 */
        private int[] offsets;
        private String[] utf8;

        Parse(byte[] data) {
            this.data = data;
            this.reader = new Reader(data, 0, data.length);
        }

        ClassFileInfo run() throws ClassFileException {
            Header header = parseHeader(data, data.length);
            reader.skip(8);
            int cpCount = reader.u2();
            readConstantPool(cpCount);

            int access = reader.u2();
            int thisIndex = reader.u2();
            String thisClass = className(thisIndex, reader.pos - 2);
            int superIndex = reader.u2();
            String superClass = superIndex == 0 ? null : className(superIndex, reader.pos - 2);

            int interfaceCount = reader.u2();
            reader.ensureCount(interfaceCount, 2, "interfaces");
            List<String> interfaces = new ArrayList<>(interfaceCount);
            for (int i = 0; i < interfaceCount; i++) {
                interfaces.add(className(reader.u2(), reader.pos - 2));
            }

            List<ClassFileInfo.Member> fields = readMembers("fields");
            List<ClassFileInfo.Member> methods = readMembers("methods");

            String sourceFile = null;
            String signature = null;
            boolean deprecated = false;
            List<String> annotations = new ArrayList<>();
            List<ClassFileInfo.Member> recordComponents = null;
            List<String> permitted = null;
            String nestHost = null;
            List<String> nestMembers = new ArrayList<>();
            String moduleMainClass = null;
            ModuleBuilder module = null;
            List<String> attributeNames = new ArrayList<>();

            int attributeCount = reader.u2();
            reader.ensureCount(attributeCount, 6, "attributes");
            for (int i = 0; i < attributeCount; i++) {
                int nameOffset = reader.pos;
                String name = utf8(reader.u2(), nameOffset);
                long length = reader.u4Unsigned();
                Reader attr = reader.slice(length, name);
                attributeNames.add(name);
                switch (name) {
                    case "SourceFile":
                        sourceFile = utf8(attr.u2(), attr.pos - 2);
                        break;
                    case "Signature":
                        signature = utf8(attr.u2(), attr.pos - 2);
                        break;
                    case "Deprecated":
                        deprecated = true;
                        break;
                    case "RuntimeVisibleAnnotations":
                        readAnnotationTypes(attr, annotations);
                        break;
                    case "Record":
                        recordComponents = readRecord(attr);
                        break;
                    case "PermittedSubclasses":
                        permitted = readClassList(attr, "PermittedSubclasses");
                        break;
                    case "NestHost":
                        nestHost = className(attr.u2(), attr.pos - 2);
                        break;
                    case "NestMembers":
                        nestMembers.addAll(readClassList(attr, "NestMembers"));
                        break;
                    case "Module":
                        module = readModule(attr);
                        break;
                    case "ModuleMainClass":
                        moduleMainClass = className(attr.u2(), attr.pos - 2);
                        break;
                    default:
                        break;
                }
            }

            ClassFileInfo.ModuleInfo moduleInfo = module == null ? null : module.build(moduleMainClass);
            return new ClassFileInfo(header.minorVersion(), header.majorVersion(), cpCount, access,
                    thisClass, superClass, interfaces, fields, methods, sourceFile, signature, deprecated,
                    annotations, recordComponents, permitted, nestHost, nestMembers, moduleInfo,
                    attributeNames, referencedClasses(thisIndex));
        }

        // ------------------------------------------
        // 常量池
        // ------------------------------------------
        private void readConstantPool(int count) throws ClassFileException {
            if (count == 0) {
                throw new ClassFileException(ClassFileException.Code.BAD_CONSTANT_INDEX, reader.pos - 2,
                        "constant_pool_count must be >= 1");
            }
            // 每个条目至少 3 字节（tag + u2）；剩余字节放不下的计数直接拒绝，不按它分配内存。
            reader.ensureCount(count - 1, 3, "constant pool");
            tags = new int[count];
            offsets = new int[count];
            utf8 = new String[count];
            for (int i = 1; i < count; i++) {
                int tagOffset = reader.pos;
                int tag = reader.u1();
                tags[i] = tag;
                offsets[i] = reader.pos;
                switch (tag) {
                    case TAG_UTF8: {
                        int length = reader.u2();
                        int start = reader.pos;
                        reader.skip(length);
                        utf8[i] = decodeUtf8(start, length);
                        break;
                    }
                    case TAG_INTEGER:
                    case TAG_FLOAT:
                    case TAG_FIELDREF:
                    case TAG_METHODREF:
                    case TAG_INTERFACE_METHODREF:
                    case TAG_NAME_AND_TYPE:
                    case TAG_DYNAMIC:
                    case TAG_INVOKE_DYNAMIC:
                        reader.skip(4);
                        break;
                    case TAG_LONG:
                    case TAG_DOUBLE:
                        reader.skip(8);
                        // 占两个槽位；若它是最后一个条目，第二个槽位会越过 count，这是非法的。
                        if (i + 1 >= count) {
                            throw new ClassFileException(ClassFileException.Code.BAD_CONSTANT_INDEX, tagOffset,
                                    "long/double constant at last slot " + i);
                        }
                        tags[++i] = TAG_RESERVED;
                        break;
                    case TAG_CLASS:
                    case TAG_STRING:
                    case TAG_METHOD_TYPE:
                    case TAG_MODULE:
                    case TAG_PACKAGE:
                        reader.skip(2);
                        break;
                    case TAG_METHOD_HANDLE:
                        reader.skip(3);
                        break;
                    default:
                        throw new ClassFileException(ClassFileException.Code.BAD_CONSTANT_TAG, tagOffset,
                                "Unknown constant pool tag " + tag + " at index " + i);
                }
            }
        }

        private String decodeUtf8(int start, int length) throws ClassFileException {
            // DataInputStream.readUTF 正是 JVM 使用的“修改版 UTF-8”（\0 编码为两字节、补充字符为代理对）。
            byte[] framed = new byte[length + 2];
            framed[0] = (byte) (length >>> 8);
            framed[1] = (byte) length;
            System.arraycopy(data, start, framed, 2, length);
            try {
                return new DataInputStream(new ByteArrayInputStream(framed)).readUTF();
            } catch (IOException malformed) {
                throw new ClassFileException(ClassFileException.Code.BAD_UTF8, start,
                        "Malformed modified UTF-8");
            }
        }

        private void checkIndex(int index, int expectedTag, long at) throws ClassFileException {
            if (index <= 0 || index >= tags.length) {
                throw new ClassFileException(ClassFileException.Code.BAD_CONSTANT_INDEX, at,
                        "Constant pool index " + index + " out of range");
            }
            if (tags[index] != expectedTag) {
                throw new ClassFileException(ClassFileException.Code.BAD_CONSTANT_INDEX, at,
                        "Constant pool index " + index + " has tag " + tags[index] + ", expected " + expectedTag);
            }
        }

        String utf8(int index, long at) throws ClassFileException {
            checkIndex(index, TAG_UTF8, at);
            return utf8[index];
        }

        /** Class 常量 → 点分类名；数组类型渲染成 {@code String[]} 形式。 */
        String className(int index, long at) throws ClassFileException {
            checkIndex(index, TAG_CLASS, at);
            return internalToDotted(utf8(u2At(offsets[index]), offsets[index]));
        }

        private String moduleName(int index, long at) throws ClassFileException {
            checkIndex(index, TAG_MODULE, at);
            return utf8(u2At(offsets[index]), offsets[index]);
        }

        private String packageName(int index, long at) throws ClassFileException {
            checkIndex(index, TAG_PACKAGE, at);
            return utf8(u2At(offsets[index]), offsets[index]).replace('/', '.');
        }

        private int u2At(int offset) {
            return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
        }

        private List<String> referencedClasses(int thisIndex) {
            TreeSet<String> names = new TreeSet<>();
            for (int i = 1; i < tags.length; i++) {
                if (tags[i] != TAG_CLASS || i == thisIndex) {
                    continue;
                }
                int nameIndex = u2At(offsets[i]);
                if (nameIndex <= 0 || nameIndex >= tags.length || tags[nameIndex] != TAG_UTF8) {
                    continue;
                }
                String name = utf8[nameIndex];
                if (name.startsWith("[")) {
                    int dims = 0;
                    while (dims < name.length() && name.charAt(dims) == '[') {
                        dims++;
                    }
                    String element = name.substring(dims);
                    if (!element.startsWith("L") || !element.endsWith(";")) {
                        continue;
                    }
                    name = element.substring(1, element.length() - 1);
                }
                names.add(name.replace('/', '.'));
            }
            return new ArrayList<>(names);
        }

        // ------------------------------------------
        // 字段与方法
        // ------------------------------------------
        private List<ClassFileInfo.Member> readMembers(String what) throws ClassFileException {
            int count = reader.u2();
            // access + name + descriptor + attributes_count = 8 字节
            reader.ensureCount(count, 8, what);
            List<ClassFileInfo.Member> members = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                members.add(readMember(reader, true));
            }
            return members;
        }

        private ClassFileInfo.Member readMember(Reader in, boolean hasAccess) throws ClassFileException {
            int access = hasAccess ? in.u2() : 0;
            String name = utf8(in.u2(), in.pos - 2);
            String descriptor = utf8(in.u2(), in.pos - 2);
            String signature = null;
            boolean deprecated = false;
            int attributeCount = in.u2();
            in.ensureCount(attributeCount, 6, "member attributes");
            for (int i = 0; i < attributeCount; i++) {
                int nameOffset = in.pos;
                String attrName = utf8(in.u2(), nameOffset);
                long length = in.u4Unsigned();
                Reader attr = in.slice(length, attrName);
                if ("Signature".equals(attrName)) {
                    signature = utf8(attr.u2(), attr.pos - 2);
                } else if ("Deprecated".equals(attrName)) {
                    deprecated = true;
                }
            }
            return new ClassFileInfo.Member(access, name, descriptor, signature, deprecated);
        }

        // ------------------------------------------
        // 属性
        // ------------------------------------------
        private List<ClassFileInfo.Member> readRecord(Reader attr) throws ClassFileException {
            int count = attr.u2();
            attr.ensureCount(count, 6, "record components");
            List<ClassFileInfo.Member> components = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                components.add(readMember(attr, false));
            }
            return components;
        }

        private List<String> readClassList(Reader attr, String what) throws ClassFileException {
            int count = attr.u2();
            attr.ensureCount(count, 2, what);
            List<String> names = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                names.add(className(attr.u2(), attr.pos - 2));
            }
            return names;
        }

        private void readAnnotationTypes(Reader attr, List<String> out) throws ClassFileException {
            int count = attr.u2();
            attr.ensureCount(count, 4, "annotations");
            for (int i = 0; i < count; i++) {
                String type = readAnnotation(attr, 0);
                out.add(type);
            }
        }

        /** 读一个 annotation 结构，返回注解类型全名；元素值只校验并跳过。 */
        private String readAnnotation(Reader in, int depth) throws ClassFileException {
            if (depth > MAX_ANNOTATION_DEPTH) {
                throw new ClassFileException(ClassFileException.Code.LIMIT_EXCEEDED, in.pos,
                        "Annotation nesting deeper than " + MAX_ANNOTATION_DEPTH);
            }
            String type = JvmDescriptors.className(utf8(in.u2(), in.pos - 2));
            int pairs = in.u2();
            in.ensureCount(pairs, 3, "annotation element pairs");
            for (int i = 0; i < pairs; i++) {
                in.u2();
                skipElementValue(in, depth + 1);
            }
            return type;
        }

        private void skipElementValue(Reader in, int depth) throws ClassFileException {
            if (depth > MAX_ANNOTATION_DEPTH) {
                throw new ClassFileException(ClassFileException.Code.LIMIT_EXCEEDED, in.pos,
                        "Annotation nesting deeper than " + MAX_ANNOTATION_DEPTH);
            }
            int tagOffset = in.pos;
            int tag = in.u1();
            switch (tag) {
                case 'B': case 'C': case 'D': case 'F': case 'I': case 'J': case 'S': case 'Z':
                case 's': case 'c':
                    in.skip(2);
                    break;
                case 'e':
                    in.skip(4);
                    break;
                case '@':
                    readAnnotation(in, depth + 1);
                    break;
                case '[': {
                    int values = in.u2();
                    in.ensureCount(values, 3, "annotation array");
                    for (int i = 0; i < values; i++) {
                        skipElementValue(in, depth + 1);
                    }
                    break;
                }
                default:
                    throw new ClassFileException(ClassFileException.Code.BAD_ATTRIBUTE, tagOffset,
                            "Unknown annotation element tag " + tag);
            }
        }

        private ModuleBuilder readModule(Reader attr) throws ClassFileException {
            ModuleBuilder module = new ModuleBuilder();
            module.name = moduleName(attr.u2(), attr.pos - 2);
            module.flags = attr.u2();
            int versionIndex = attr.u2();
            module.version = versionIndex == 0 ? null : utf8(versionIndex, attr.pos - 2);

            int requires = attr.u2();
            attr.ensureCount(requires, 6, "module requires");
            for (int i = 0; i < requires; i++) {
                String name = moduleName(attr.u2(), attr.pos - 2);
                int flags = attr.u2();
                int version = attr.u2();
                module.requires.add(new ClassFileInfo.Requires(name, flags,
                        version == 0 ? null : utf8(version, attr.pos - 2)));
            }
            readExports(attr, module.exports, "module exports");
            readExports(attr, module.opens, "module opens");

            int uses = attr.u2();
            attr.ensureCount(uses, 2, "module uses");
            for (int i = 0; i < uses; i++) {
                module.uses.add(className(attr.u2(), attr.pos - 2));
            }
            int provides = attr.u2();
            attr.ensureCount(provides, 4, "module provides");
            for (int i = 0; i < provides; i++) {
                String service = className(attr.u2(), attr.pos - 2);
                int withCount = attr.u2();
                attr.ensureCount(withCount, 2, "module provides with");
                List<String> impls = new ArrayList<>(withCount);
                for (int j = 0; j < withCount; j++) {
                    impls.add(className(attr.u2(), attr.pos - 2));
                }
                module.provides.add(new ClassFileInfo.Provides(service, impls));
            }
            return module;
        }

        private void readExports(Reader attr, List<ClassFileInfo.Exports> out, String what)
                throws ClassFileException {
            int count = attr.u2();
            attr.ensureCount(count, 6, what);
            for (int i = 0; i < count; i++) {
                String pkg = packageName(attr.u2(), attr.pos - 2);
                attr.u2();
                int toCount = attr.u2();
                attr.ensureCount(toCount, 2, what);
                List<String> targets = new ArrayList<>(toCount);
                for (int j = 0; j < toCount; j++) {
                    targets.add(moduleName(attr.u2(), attr.pos - 2));
                }
                out.add(new ClassFileInfo.Exports(pkg, targets));
            }
        }
    }

    private static final class ModuleBuilder {
        String name;
        int flags;
        String version;
        final List<ClassFileInfo.Requires> requires = new ArrayList<>();
        final List<ClassFileInfo.Exports> exports = new ArrayList<>();
        final List<ClassFileInfo.Exports> opens = new ArrayList<>();
        final List<String> uses = new ArrayList<>();
        final List<ClassFileInfo.Provides> provides = new ArrayList<>();

        ClassFileInfo.ModuleInfo build(String mainClass) {
            return new ClassFileInfo.ModuleInfo(name, flags, version, requires, exports, opens, uses,
                    provides, mainClass);
        }
    }

    /** 内部名 → 点分名；数组描述符渲染为 Java 写法。 */
    static String internalToDotted(String internal) {
        if (internal.startsWith("[")) {
            return JvmDescriptors.fieldType(internal);
        }
        return internal.replace('/', '.');
    }

    // ==========================================
    // 带边界检查的读取器
    // ==========================================
    private static final class Reader {
        private final byte[] data;
        private final int limit;
        int pos;

        Reader(byte[] data, int start, int limit) {
            this.data = data;
            this.pos = start;
            this.limit = limit;
        }

        private void need(int bytes) throws ClassFileException {
            if (bytes < 0 || limit - pos < bytes) {
                throw new ClassFileException(ClassFileException.Code.TRUNCATED, pos,
                        "Need " + bytes + " bytes, " + Math.max(0, limit - pos) + " left");
            }
        }

        int u1() throws ClassFileException {
            need(1);
            return data[pos++] & 0xFF;
        }

        int u2() throws ClassFileException {
            need(2);
            int value = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return value;
        }

        int u4() throws ClassFileException {
            need(4);
            int value = ((data[pos] & 0xFF) << 24) | ((data[pos + 1] & 0xFF) << 16)
                    | ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
            pos += 4;
            return value;
        }

        long u4Unsigned() throws ClassFileException {
            return u4() & 0xFFFFFFFFL;
        }

        void skip(int bytes) throws ClassFileException {
            need(bytes);
            pos += bytes;
        }

        /** 切出一个长度为 {@code length} 的子读取器，并把自己推进到其末尾。 */
        Reader slice(long length, String what) throws ClassFileException {
            if (length > limit - pos) {
                throw new ClassFileException(ClassFileException.Code.TRUNCATED, pos,
                        "Attribute " + what + " declares " + length + " bytes, " + (limit - pos) + " left");
            }
            Reader sub = new Reader(data, pos, pos + (int) length);
            pos += (int) length;
            return sub;
        }

        /**
         * 计数 × 每项最小字节数不得超过剩余字节，在按计数分配任何东西之前就拒绝。
         *
         * <p>整个输入都装不下的计数判为伪造（{@code LIMIT_EXCEEDED}）；只是剩余部分装不下的，
         * 更可能是文件被截断（{@code TRUNCATED}）——两者给用户的处理建议不同。</p>
         */
        void ensureCount(int count, int minBytesEach, String what) throws ClassFileException {
            long needed = (long) count * minBytesEach;
            if (needed > limit - pos) {
                ClassFileException.Code code = needed > data.length
                        ? ClassFileException.Code.LIMIT_EXCEEDED : ClassFileException.Code.TRUNCATED;
                throw new ClassFileException(code, pos - 2,
                        what + " count " + count + " cannot fit in " + (limit - pos) + " remaining bytes");
            }
        }
    }
}
