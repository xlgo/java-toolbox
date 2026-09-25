package com.aqishi.toolbox.feature.security.ui;

import com.aqishi.toolbox.feature.security.domain.JwkService;
import com.aqishi.toolbox.feature.security.infra.JwksFetcher;
import com.aqishi.toolbox.ui.kit.Tokens;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwkPanelTest {

    @Test
    void buildsViewWithCatalogIdentity() {
        JwkPanel panel = new JwkPanel();

        assertEquals("security", panel.getGroup());
        assertEquals("jwk.tool", panel.getName());
        assertNotNull(panel.getView());
    }

    @Test
    void closeResourcesIsIdempotent() {
        JwkPanel panel = new JwkPanel();
        panel.getView();

        assertDoesNotThrow(() -> {
            panel.closeResources();
            panel.closeResources();
        });
    }

    @Test
    void parsesKeysVerifiesTokenAndMergesConvertedPem() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair();
        JwkService service = new JwkService();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        String jwk = service.pemToJwk(pem, "r1", "sig", "RS256");
        String token = rs256(pair, "{\"alg\":\"RS256\",\"kid\":\"r1\"}",
                "{\"sub\":\"u\",\"exp\":" + (System.currentTimeMillis() / 1000 + 600) + "}");

        JwkPanel panel = new JwkPanel(service, new JwksFetcher());
        panel.getView();
        try {
            SwingUtilities.invokeAndWait(() -> {
                field(panel, "jwksArea", JTextArea.class).setText("{\"keys\":[" + jwk + "]}");
                invoke(panel, "parseKeys", true);
            });
            assertEquals(1, field(panel, "keyModel", DefaultTableModel.class).getRowCount());
            assertTrue(field(panel, "detailArea", JTextArea.class).getText().contains("BEGIN PUBLIC KEY"));

            SwingUtilities.invokeAndWait(() -> {
                field(panel, "tokenArea", JTextArea.class).setText(token);
                invoke(panel, "verify");
            });
            assertEquals(Tokens.success(), field(panel, "verdictLabel", JLabel.class).getForeground());
            assertTrue(field(panel, "decodedArea", JTextArea.class).getText().contains("\"sub\""));

            SwingUtilities.invokeAndWait(() -> {
                field(panel, "pemArea", JTextArea.class).setText(pem);
                invoke(panel, "convertPem");
                invoke(panel, "addConvertedToKeys");
            });
            assertEquals(2, field(panel, "keyModel", DefaultTableModel.class).getRowCount());
        } finally {
            panel.closeResources();
        }
    }

    @Test
    void closingPanelDropsInFlightFetchResult() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch requested = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            requested.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"keys\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            } catch (java.io.IOException ignored) {
                // 客户端已取消
            }
        });
        server.start();
        JwkPanel panel = new JwkPanel(new JwkService(), new JwksFetcher());
        panel.getView();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
            SwingUtilities.invokeAndWait(() -> {
                field(panel, "jwksArea", JTextArea.class).setText("untouched");
                field(panel, "urlField", JTextField.class).setText(url);
                invoke(panel, "startFetch");
            });
            assertTrue(requested.await(5, TimeUnit.SECONDS));
            panel.closeResources();
            release.countDown();
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> { });

            assertEquals("untouched", field(panel, "jwksArea", JTextArea.class).getText());
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    private static String rs256(KeyPair pair, String header, String payload) throws Exception {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String input = encoder.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encoder.encodeToString(signer.sign());
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void invoke(Object target, String name, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] instanceof Boolean ? boolean.class : args[i].getClass();
            }
            Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, args);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }
}
