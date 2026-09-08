package com.aqishi.toolbox.feature.security.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BatchDigestServiceTest {

    private final BatchDigestService service = new BatchDigestService();

    @TempDir
    Path tempDir;

    @Test
    void computesMultipleDigestsCorrectly() throws Exception {
        Path testFile = tempDir.resolve("sample.txt");
        Files.write(testFile, "Hello World from Java Toolbox!".getBytes(StandardCharsets.UTF_8));

        List<String> algs = Arrays.asList("MD5", "SHA-1", "SHA-256", "SM3");
        BatchDigestService.DigestResult res = service.computeFileDigest(testFile.toFile(), algs);

        assertTrue(res.isSuccess());
        assertEquals("sample.txt", res.getFileName());
        assertEquals(4, res.getHashes().size());
        assertNotNull(res.getHashes().get("MD5"));
        assertNotNull(res.getHashes().get("SHA-256"));
        assertNotNull(res.getHashes().get("SM3"));
        assertEquals(64, res.getHashes().get("SHA-256").length());
        assertEquals(64, res.getHashes().get("SM3").length());
    }

    @Test
    void parsesAndMatchesChecksumFile() throws Exception {
        Path file1 = tempDir.resolve("test1.bin");
        Path file2 = tempDir.resolve("test2.bin");
        Files.write(file1, "file1 content".getBytes(StandardCharsets.UTF_8));
        Files.write(file2, "file2 content".getBytes(StandardCharsets.UTF_8));

        BatchDigestService.DigestResult r1 = service.computeFileDigest(file1.toFile(), Collections.singletonList("SHA-256"));
        BatchDigestService.DigestResult r2 = service.computeFileDigest(file2.toFile(), Collections.singletonList("SHA-256"));

        String hash1 = r1.getHashes().get("SHA-256");
        String fakeHash = "0000000000000000000000000000000000000000000000000000000000000000";

        String checksumContent = hash1 + "  test1.bin\n" + fakeHash + "  test2.bin\n";
        Map<String, String> parsed = service.parseChecksumFile(checksumContent);
        assertEquals(2, parsed.size());

        List<BatchDigestService.DigestResult> results = Arrays.asList(r1, r2);
        service.matchChecksums(results, parsed, "SHA-256");

        assertEquals("PASS", r1.getMatchStatus());
        assertEquals("FAIL", r2.getMatchStatus());
    }

    @Test
    void signsAndVerifiesFileSuccessfully() throws Exception {
        Path testFile = tempDir.resolve("payload.dat");
        Files.write(testFile, "Critical Payload for Digital Signature".getBytes(StandardCharsets.UTF_8));

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        KeyPair kp = kpg.generateKeyPair();

        byte[] signature = service.signFile(testFile.toFile(), kp.getPrivate(), "SHA256withRSA");
        assertNotNull(signature);
        assertTrue(signature.length > 0);

        boolean valid = service.verifyFileSignature(testFile.toFile(), kp.getPublic(), signature, "SHA256withRSA");
        assertTrue(valid);

        // 模拟篡改文件
        Files.write(testFile, "Tampered Payload!".getBytes(StandardCharsets.UTF_8));
        boolean tamperedValid = service.verifyFileSignature(testFile.toFile(), kp.getPublic(), signature, "SHA256withRSA");
        assertFalse(tamperedValid);
    }
}
