package com.kisapilot.kisaagent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
final class RebootSupport {
    static void require(String ip, String message) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("http://" + ip + ":5005/reboot/capabilities").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            if (connection.getResponseCode() != 200) throw new IOException(message);
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                char[] chars = new char[1024]; int count;
                while ((count = reader.read(chars)) != -1) {
                    body.append(chars, 0, count);
                    if (body.length() > 65536) throw new IOException(message);
                }
            }
            if (!new JSONObject(body.toString()).optBoolean("disengageBeforeReboot", false)) throw new IOException(message);
        } catch (Exception error) {
            throw new IOException(message, error);
        } finally { if (connection != null) connection.disconnect(); }
    }
}
