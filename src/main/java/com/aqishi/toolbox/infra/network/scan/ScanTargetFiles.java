package com.aqishi.toolbox.infra.network.scan;

import com.aqishi.toolbox.feature.network.domain.PortExpressionParser;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/** Bounded UTF-8 target import, including comments and BOM. */
public final class ScanTargetFiles {
    private ScanTargetFiles() {}

    public static String read(Path path) throws IOException {
        byte[] bytes;
        try (InputStream input = Files.newInputStream(path)) {
            bytes = input.readNBytes(1024 * 1024 + 1);
        }
        if (bytes.length > 1024 * 1024) throw new IOException("portscan.error.fileSize");
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            throw new IOException("portscan.error.fileEncoding", error);
        }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        StringBuilder cleaned = new StringBuilder();
        for (String line : text.split("\\R")) {
            int comment = line.indexOf('#');
            if (comment >= 0) line = line.substring(0, comment);
            if (!line.stripLeading().startsWith("//")) cleaned.append(line).append('\n');
        }
        return String.join(",", PortExpressionParser.tokens(cleaned.toString()));
    }
}
