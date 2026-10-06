import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

/**
 * 极简 WebDAV 服务器，仅用于验证 WorldBackup 的上传 / 列表 / 删除逻辑。
 * 支持：MKCOL / PUT / GET / DELETE / PROPFIND(Depth:1) + Basic 认证。
 *
 * 运行：java MockWebDav.java <rootDir> <user> <password> <port>
 */
public final class MockWebDav {

    private static Path root;
    private static String expectedAuth;
    private static final DateTimeFormatter HTTP_DATE =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US).withZone(ZoneOffset.UTC);

    public static void main(String[] args) throws Exception {
        root = Paths.get(args.length > 0 ? args[0] : "dav-root").toAbsolutePath().normalize();
        Files.createDirectories(root);
        String user = args.length > 1 ? args[1] : "tester";
        String password = args.length > 2 ? args[2] : "secret";
        int port = args.length > 3 ? Integer.parseInt(args[3]) : 18080;
        expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/dav/", MockWebDav::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("MockWebDAV 已启动: http://127.0.0.1:" + port + "/dav/  root=" + root);
        System.out.flush();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String uri = exchange.getRequestURI().getPath();
        try {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (expectedAuth != null && !expectedAuth.equals(auth)) {
                System.out.println(method + " " + uri + " -> 401");
                respond(exchange, 401, "authentication required");
                return;
            }
            String relative = URLDecoder.decode(uri.substring("/dav/".length()).replace("+", "%2B"), StandardCharsets.UTF_8);
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) {
                respond(exchange, 403, "forbidden");
                return;
            }

            switch (method) {
                case "MKCOL" -> {
                    if (Files.isDirectory(target)) {
                        System.out.println("MKCOL " + relative + " -> 405 (已存在)");
                        respond(exchange, 405, "");
                    } else {
                        Files.createDirectories(target);
                        System.out.println("MKCOL " + relative + " -> 201");
                        respond(exchange, 201, "");
                    }
                }
                case "PUT" -> {
                    Files.createDirectories(target.getParent());
                    long bytes = Files.copy(exchange.getRequestBody(), target, StandardCopyOption.REPLACE_EXISTING);
                    System.out.println("PUT " + relative + " -> 201 (" + bytes + " bytes)");
                    respond(exchange, 201, "");
                }
                case "PROPFIND" -> {
                    String xml = list(target, uri);
                    byte[] body = xml.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().add("Content-Type", "application/xml; charset=utf-8");
                    exchange.sendResponseHeaders(207, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                    System.out.println("PROPFIND " + relative + " -> 207");
                }
                case "DELETE" -> {
                    boolean deleted = Files.deleteIfExists(target);
                    System.out.println("DELETE " + relative + " -> " + (deleted ? 204 : 404));
                    respond(exchange, deleted ? 204 : 404, "");
                }
                case "GET" -> {
                    if (Files.isRegularFile(target)) {
                        byte[] body = Files.readAllBytes(target);
                        exchange.sendResponseHeaders(200, body.length);
                        try (OutputStream out = exchange.getResponseBody()) {
                            out.write(body);
                        }
                    } else {
                        respond(exchange, 404, "not found");
                    }
                }
                default -> {
                    System.out.println(method + " " + relative + " -> 405");
                    respond(exchange, 405, "method not allowed");
                }
            }
        } catch (Exception e) {
            System.out.println(method + " " + uri + " -> 500 " + e);
            respond(exchange, 500, String.valueOf(e));
        }
    }

    private static String list(Path dir, String requestUri) throws IOException {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n");
        String base = requestUri.endsWith("/") ? requestUri : requestUri + "/";
        xml.append(response(base, dir, true));

        if (Files.isDirectory(dir)) {
            List<Path> children = new ArrayList<>();
            try (Stream<Path> stream = Files.list(dir)) {
                stream.forEach(children::add);
            }
            children.sort(Comparator.comparing(p -> p.getFileName().toString()));
            for (Path child : children) {
                String href = base + child.getFileName().toString() + (Files.isDirectory(child) ? "/" : "");
                xml.append(response(href, child, Files.isDirectory(child)));
            }
        }
        xml.append("</D:multistatus>");
        return xml.toString();
    }

    private static String response(String href, Path path, boolean directory) throws IOException {
        long lastModified = Files.exists(path) ? Files.getLastModifiedTime(path).toMillis() : System.currentTimeMillis();
        long size = Files.isRegularFile(path) ? Files.size(path) : 0L;
        String encoded = href.replace(" ", "%20");
        return "  <D:response>\n"
                + "    <D:href>" + encoded + "</D:href>\n"
                + "    <D:propstat><D:prop>\n"
                + "      <D:getlastmodified>" + HTTP_DATE.format(java.time.Instant.ofEpochMilli(lastModified)) + "</D:getlastmodified>\n"
                + "      <D:getcontentlength>" + size + "</D:getcontentlength>\n"
                + "      <D:resourcetype>" + (directory ? "<D:collection/>" : "") + "</D:resourcetype>\n"
                + "    </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>\n"
                + "  </D:response>\n";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
