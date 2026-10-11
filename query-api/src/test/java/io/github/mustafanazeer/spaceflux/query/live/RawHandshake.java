package io.github.mustafanazeer.spaceflux.query.live;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A WebSocket handshake written by hand on a plain socket, for what the JDK client will not send (a bad version, no
 * Origin at all, a cookie it cannot see) and for reading the response's headers and body exactly.
 */
final class RawHandshake implements AutoCloseable {

    final Socket socket;
    final String head;
    final String body;

    private RawHandshake(Socket socket, String head, String body) {
        this.socket = socket;
        this.head = head;
        this.body = body;
    }

    static RawHandshake send(String base, Map<String, String> extra) throws IOException {
        return send(base, extra, 0);
    }

    /** With receiveBuffer above 0, the socket's receive buffer is set before connecting. */
    static RawHandshake send(String base, Map<String, String> extra, int receiveBuffer) throws IOException {
        URI uri = URI.create(base);
        Socket s = new Socket();
        if (receiveBuffer > 0) {
            s.setReceiveBufferSize(receiveBuffer);
        }
        s.connect(new java.net.InetSocketAddress(uri.getHost(), uri.getPort()), 5_000);
        s.setSoTimeout(5_000);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Host", uri.getHost() + ":" + uri.getPort());
        headers.put("Upgrade", "websocket");
        headers.put("Connection", "Upgrade");
        headers.put("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==");
        headers.put("Sec-WebSocket-Version", "13");
        headers.put("Sec-WebSocket-Protocol", "graphql-transport-ws");
        headers.putAll(extra);
        StringBuilder request = new StringBuilder("GET " + TestSocket.PATH + " HTTP/1.1\r\n");
        headers.forEach((k, v) -> {
            if (v != null) {
                request.append(k).append(": ").append(v).append("\r\n");
            }
        });
        request.append("\r\n");
        s.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
        s.getOutputStream().flush();
        InputStream in = s.getInputStream();
        ByteArrayOutputStream headBytes = new ByteArrayOutputStream();
        String head = "";
        while (!head.endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            headBytes.write(b);
            head = headBytes.toString(StandardCharsets.ISO_8859_1);
        }
        String body = "";
        if (!head.startsWith("HTTP/1.1 101")) {
            body = readBody(in, head);
        }
        return new RawHandshake(s, head, body);
    }

    /** The body by its Content-Length, or chunk by chunk until the last chunk. */
    private static String readBody(InputStream in, String head) throws IOException {
        String length = header(head, "Content-Length");
        if (length != null) {
            return new String(in.readNBytes(Integer.parseInt(length)), StandardCharsets.UTF_8);
        }
        if (!"chunked".equalsIgnoreCase(header(head, "Transfer-Encoding"))) {
            return "";
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        while (true) {
            int size = Integer.parseInt(line(in).strip(), 16);
            if (size == 0) {
                line(in);
                return body.toString(StandardCharsets.UTF_8);
            }
            body.write(in.readNBytes(size));
            line(in);
        }
    }

    private static String line(InputStream in) throws IOException {
        StringBuilder out = new StringBuilder();
        int b;
        while ((b = in.read()) >= 0 && b != '\n') {
            if (b != '\r') {
                out.append((char) b);
            }
        }
        return out.toString();
    }

    int status() {
        return Integer.parseInt(head.substring(9, 12));
    }

    String header(String name) {
        return header(head, name);
    }

    private static String header(String head, String name) {
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                return line.substring(colon + 1).strip();
            }
        }
        return null;
    }

    /** Sends one masked text frame. */
    void sendText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(0x81);
        if (payload.length < 126) {
            frame.write(0x80 | payload.length);
        } else {
            frame.write(0x80 | 126);
            frame.write(payload.length >> 8);
            frame.write(payload.length & 0xFF);
        }
        byte[] mask = {1, 2, 3, 4};
        frame.write(mask);
        for (int i = 0; i < payload.length; i++) {
            frame.write(payload[i] ^ mask[i % 4]);
        }
        socket.getOutputStream().write(frame.toByteArray());
        socket.getOutputStream().flush();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
