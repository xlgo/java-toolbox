package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * JVM 描述符（JVMS §4.3）到 Java 风格文本的转换。
 *
 * <p>{@code (ILjava/lang/String;)V} → {@code void (int, String)}。{@code java.lang} 包下的类型
 * 省略包名（与源码里的写法一致），其余类型保留全限定名——排查 {@code NoSuchMethodError}
 * 时，参数到底是哪个包的 {@code List} 正是关键信息。</p>
 *
 * <p>非法描述符不抛异常，原样返回：界面展示的是“能看懂就行”，不应该因为一个被混淆器
 * 写坏的描述符让整个类的方法表都显示不出来。</p>
 */
public final class JvmDescriptors {

    private JvmDescriptors() {
    }

    /** 字段描述符：{@code [Ljava/lang/String;} → {@code String[]}；非法时原样返回。 */
    public static String fieldType(String descriptor) {
        if (descriptor == null || descriptor.isEmpty()) {
            return descriptor;
        }
        try {
            int[] cursor = {0};
            String type = parseType(descriptor, cursor, false);
            return cursor[0] == descriptor.length() ? type : descriptor;
        } catch (IllegalArgumentException malformed) {
            return descriptor;
        }
    }

    /** 方法描述符：{@code (ILjava/lang/String;)V} → {@code void (int, String)}；非法时原样返回。 */
    public static String method(String descriptor) {
        return method(null, descriptor);
    }

    /**
     * 带方法名的方法描述符：{@code void run(int, String)}。
     *
     * @param name 方法名；为 {@code null} 时名字与括号之间留一个空格，如 {@code void (int)}
     */
    public static String method(String name, String descriptor) {
        if (descriptor == null || !descriptor.startsWith("(")) {
            return descriptor;
        }
        try {
            List<String> params = new ArrayList<>();
            int[] cursor = {1};
            while (cursor[0] < descriptor.length() && descriptor.charAt(cursor[0]) != ')') {
                params.add(parseType(descriptor, cursor, false));
            }
            if (cursor[0] >= descriptor.length()) {
                return descriptor;
            }
            cursor[0]++;
            String returnType = parseType(descriptor, cursor, true);
            if (cursor[0] != descriptor.length()) {
                return descriptor;
            }
            StringBuilder text = new StringBuilder(returnType).append(' ');
            if (name != null) {
                text.append(name);
            }
            text.append('(').append(String.join(", ", params)).append(')');
            return text.toString();
        } catch (IllegalArgumentException malformed) {
            return descriptor;
        }
    }

    /**
     * 类型描述符里的类名（{@code Lcom/foo/Bar;} → {@code com.foo.Bar}），不做 java.lang 省略；
     * 用于注解类型名等需要全限定名的地方。非 L 描述符原样返回。
     */
    public static String className(String descriptor) {
        if (descriptor != null && descriptor.length() > 2 && descriptor.charAt(0) == 'L'
                && descriptor.charAt(descriptor.length() - 1) == ';') {
            return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
        }
        return descriptor;
    }

    /** 内部名 {@code java/lang/String} → 界面名 {@code String}；其他包 → 点分全限定名。 */
    public static String simplifyInternalName(String internalName) {
        String dotted = internalName.replace('/', '.');
        if (dotted.startsWith("java.lang.") && dotted.indexOf('.', "java.lang.".length()) < 0) {
            return dotted.substring("java.lang.".length());
        }
        return dotted;
    }

    private static String parseType(String descriptor, int[] cursor, boolean allowVoid) {
        int dimensions = 0;
        while (cursor[0] < descriptor.length() && descriptor.charAt(cursor[0]) == '[') {
            dimensions++;
            cursor[0]++;
        }
        // JVMS §4.3.2：数组最多 255 维；超过的必是垃圾数据。
        if (dimensions > 255 || cursor[0] >= descriptor.length()) {
            throw new IllegalArgumentException("bad descriptor");
        }
        char tag = descriptor.charAt(cursor[0]++);
        String base;
        switch (tag) {
            case 'B': base = "byte"; break;
            case 'C': base = "char"; break;
            case 'D': base = "double"; break;
            case 'F': base = "float"; break;
            case 'I': base = "int"; break;
            case 'J': base = "long"; break;
            case 'S': base = "short"; break;
            case 'Z': base = "boolean"; break;
            case 'V':
                if (!allowVoid || dimensions > 0) {
                    throw new IllegalArgumentException("void not allowed here");
                }
                base = "void";
                break;
            case 'L': {
                int end = descriptor.indexOf(';', cursor[0]);
                if (end <= cursor[0]) {
                    throw new IllegalArgumentException("unterminated class type");
                }
                base = simplifyInternalName(descriptor.substring(cursor[0], end));
                cursor[0] = end + 1;
                break;
            }
            default:
                throw new IllegalArgumentException("unknown type tag");
        }
        return dimensions == 0 ? base : base + "[]".repeat(dimensions);
    }
}
