package com.aqishi.toolbox.feature.system.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 类冲突模型：记录首选胜出版本（Winner）与被遮蔽版本（Masked）的详细 ABI 差异与风险定级。
 */
public record ClassConflict(
        String className,
        ClasspathSource winnerSource,
        ClassAbiSignature winnerAbi,
        List<MaskedOccurrence> maskedOccurrences
) {

    public enum RiskLevel {
        BREAKING,
        WARNING,
        SAFE
    }

    public record IncompatibleMethodChange(
            ClassAbiSignature.MethodSignature winner,
            ClassAbiSignature.MethodSignature masked,
            String reason
    ) {}

    public record IncompatibleFieldChange(
            ClassAbiSignature.FieldSignature winner,
            ClassAbiSignature.FieldSignature masked,
            String reason
    ) {}

    public record MaskedOccurrence(
            ClasspathSource source,
            ClassAbiSignature abi,
            RiskLevel riskLevel,
            List<ClassAbiSignature.MethodSignature> missingMethods,
            List<IncompatibleMethodChange> incompatibleMethods,
            List<ClassAbiSignature.FieldSignature> missingFields,
            List<IncompatibleFieldChange> incompatibleFields,
            List<ClassAbiSignature.MethodSignature> winnerExclusiveMethods,
            boolean majorVersionDiffers,
            boolean identicalBytes
    ) {
        public MaskedOccurrence {
            missingMethods = (missingMethods == null) ? Collections.emptyList() : List.copyOf(missingMethods);
            incompatibleMethods = (incompatibleMethods == null) ? Collections.emptyList() : List.copyOf(incompatibleMethods);
            missingFields = (missingFields == null) ? Collections.emptyList() : List.copyOf(missingFields);
            incompatibleFields = (incompatibleFields == null) ? Collections.emptyList() : List.copyOf(incompatibleFields);
            winnerExclusiveMethods = (winnerExclusiveMethods == null) ? Collections.emptyList() : List.copyOf(winnerExclusiveMethods);
        }
    }

    public ClassConflict {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(winnerSource, "winnerSource");
        Objects.requireNonNull(winnerAbi, "winnerAbi");
        maskedOccurrences = (maskedOccurrences == null) ? Collections.emptyList() : List.copyOf(maskedOccurrences);
    }

    public RiskLevel worstRiskLevel() {
        RiskLevel worst = RiskLevel.SAFE;
        for (MaskedOccurrence occ : maskedOccurrences) {
            if (occ.riskLevel() == RiskLevel.BREAKING) {
                return RiskLevel.BREAKING;
            }
            if (occ.riskLevel() == RiskLevel.WARNING) {
                worst = RiskLevel.WARNING;
            }
        }
        return worst;
    }

    public int totalMissingMethods() {
        int count = 0;
        for (MaskedOccurrence occ : maskedOccurrences) {
            count += occ.missingMethods().size();
        }
        return count;
    }

    /**
     * 比对胜出版本与被遮蔽版本，生成 MaskedOccurrence。
     */
    public static MaskedOccurrence compare(
            ClasspathSource maskedSource,
            ClassAbiSignature maskedAbi,
            ClasspathSource winnerSource,
            ClassAbiSignature winnerAbi
    ) {
        boolean identical = Objects.equals(maskedAbi.sha256(), winnerAbi.sha256());
        if (identical) {
            return new MaskedOccurrence(
                    maskedSource,
                    maskedAbi,
                    RiskLevel.SAFE,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    false,
                    true
            );
        }

        List<ClassAbiSignature.MethodSignature> missingMethods = new ArrayList<>();
        List<IncompatibleMethodChange> incompatibleMethods = new ArrayList<>();
        List<ClassAbiSignature.FieldSignature> missingFields = new ArrayList<>();
        List<IncompatibleFieldChange> incompatibleFields = new ArrayList<>();
        List<ClassAbiSignature.MethodSignature> winnerExclusive = new ArrayList<>();

        Map<String, ClassAbiSignature.MethodSignature> winnerMethods = winnerAbi.publicMethods();
        Map<String, ClassAbiSignature.MethodSignature> maskedMethods = maskedAbi.publicMethods();

        for (Map.Entry<String, ClassAbiSignature.MethodSignature> entry : maskedMethods.entrySet()) {
            String key = entry.getKey();
            ClassAbiSignature.MethodSignature maskedSig = entry.getValue();
            ClassAbiSignature.MethodSignature winnerSig = winnerMethods.get(key);

            if (winnerSig == null) {
                // 缺失公开方法：运行时调用此方法将抛出 NoSuchMethodError
                missingMethods.add(maskedSig);
            } else {
                // 检查修饰符不兼容（如 static 变更）
                if (winnerSig.isStatic() != maskedSig.isStatic()) {
                    String reason = maskedSig.isStatic() ? "STATIC_TO_INSTANCE" : "INSTANCE_TO_STATIC";
                    incompatibleMethods.add(new IncompatibleMethodChange(winnerSig, maskedSig, reason));
                }
            }
        }

        for (Map.Entry<String, ClassAbiSignature.MethodSignature> entry : winnerMethods.entrySet()) {
            if (!maskedMethods.containsKey(entry.getKey())) {
                winnerExclusive.add(entry.getValue());
            }
        }

        Map<String, ClassAbiSignature.FieldSignature> winnerFields = winnerAbi.publicFields();
        Map<String, ClassAbiSignature.FieldSignature> maskedFields = maskedAbi.publicFields();

        for (Map.Entry<String, ClassAbiSignature.FieldSignature> entry : maskedFields.entrySet()) {
            String key = entry.getKey();
            ClassAbiSignature.FieldSignature maskedField = entry.getValue();
            ClassAbiSignature.FieldSignature winnerField = winnerFields.get(key);

            if (winnerField == null) {
                missingFields.add(maskedField);
            } else if (winnerField.isStatic() != maskedField.isStatic()) {
                String reason = maskedField.isStatic() ? "STATIC_TO_INSTANCE" : "INSTANCE_TO_STATIC";
                incompatibleFields.add(new IncompatibleFieldChange(winnerField, maskedField, reason));
            }
        }

        boolean majorVersionDiffers = winnerAbi.majorVersion() != maskedAbi.majorVersion();

        RiskLevel level;
        if (!missingMethods.isEmpty() || !incompatibleMethods.isEmpty() || !missingFields.isEmpty() || !incompatibleFields.isEmpty()) {
            level = RiskLevel.BREAKING;
        } else if (majorVersionDiffers || !Objects.equals(winnerAbi.superClassName(), maskedAbi.superClassName())) {
            level = RiskLevel.WARNING;
        } else {
            level = RiskLevel.SAFE;
        }

        return new MaskedOccurrence(
                maskedSource,
                maskedAbi,
                level,
                missingMethods,
                incompatibleMethods,
                missingFields,
                incompatibleFields,
                winnerExclusive,
                majorVersionDiffers,
                false
        );
    }
}
