package com.aqishi.toolbox.feature.system.domain;

import java.io.ByteArrayInputStream;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 类公开 ABI 契约摘要：提取主版本号、接口、继承关系以及所有 public 和 protected 方法/字段签名。
 */
public record ClassAbiSignature(
        String className,
        int majorVersion,
        int accessFlags,
        String superClassName,
        List<String> interfaces,
        Map<String, MethodSignature> publicMethods,
        Map<String, FieldSignature> publicFields,
        String sha256,
        long byteSize,
        boolean parseSuccess,
        String parseErrorMessage
) {

    public ClassAbiSignature {
        interfaces = (interfaces == null) ? Collections.emptyList() : List.copyOf(interfaces);
        publicMethods = (publicMethods == null) ? Collections.emptyMap() : Collections.unmodifiableMap(publicMethods);
        publicFields = (publicFields == null) ? Collections.emptyMap() : Collections.unmodifiableMap(publicFields);
    }

    public record MethodSignature(
            String name,
            String descriptor,
            int accessFlags,
            String renderedSignature,
            boolean isStatic,
            boolean isFinal,
            boolean isAbstract
    ) {
        public static MethodSignature from(ClassFileInfo.Member member) {
            int flags = member.accessFlags();
            boolean isStatic = (flags & ClassFileInfo.ACC_STATIC) != 0;
            boolean isFinal = (flags & ClassFileInfo.ACC_FINAL) != 0;
            boolean isAbstract = (flags & ClassFileInfo.ACC_ABSTRACT) != 0;
            String rendered = member.renderedType() + " " + member.name();
            return new MethodSignature(member.name(), member.descriptor(), flags, rendered, isStatic, isFinal, isAbstract);
        }

        public String key() {
            return name + descriptor;
        }
    }

    public record FieldSignature(
            String name,
            String descriptor,
            int accessFlags,
            String renderedType,
            boolean isStatic,
            boolean isFinal
    ) {
        public static FieldSignature from(ClassFileInfo.Member member) {
            int flags = member.accessFlags();
            boolean isStatic = (flags & ClassFileInfo.ACC_STATIC) != 0;
            boolean isFinal = (flags & ClassFileInfo.ACC_FINAL) != 0;
            return new FieldSignature(member.name(), member.descriptor(), flags, member.renderedType(), isStatic, isFinal);
        }

        public String key() {
            return name + descriptor;
        }
    }

    /**
     * 从类的原始字节流提取 ABI 签名。
     */
    public static ClassAbiSignature extract(String className, byte[] classBytes, String sha256) {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(classBytes, "classBytes");

        try {
            ClassFileParser parser = new ClassFileParser();
            ClassFileInfo info = parser.parse(new ByteArrayInputStream(classBytes));

            Map<String, MethodSignature> methods = new LinkedHashMap<>();
            for (ClassFileInfo.Member m : info.methods()) {
                if (isPublicOrProtected(m.accessFlags())) {
                    MethodSignature sig = MethodSignature.from(m);
                    methods.put(sig.key(), sig);
                }
            }

            Map<String, FieldSignature> fields = new LinkedHashMap<>();
            for (ClassFileInfo.Member f : info.fields()) {
                if (isPublicOrProtected(f.accessFlags())) {
                    FieldSignature sig = FieldSignature.from(f);
                    fields.put(sig.key(), sig);
                }
            }

            return new ClassAbiSignature(
                    info.className(),
                    info.majorVersion(),
                    info.accessFlags(),
                    info.superClassName(),
                    info.interfaces(),
                    methods,
                    fields,
                    sha256,
                    classBytes.length,
                    true,
                    null
            );
        } catch (Exception ex) {
            return new ClassAbiSignature(
                    className,
                    0,
                    0,
                    null,
                    Collections.emptyList(),
                    Collections.emptyMap(),
                    Collections.emptyMap(),
                    sha256,
                    classBytes.length,
                    false,
                    ex.getMessage()
            );
        }
    }

    private static boolean isPublicOrProtected(int flags) {
        return (flags & (ClassFileInfo.ACC_PUBLIC | ClassFileInfo.ACC_PROTECTED)) != 0;
    }
}
