package com.dsh.worldbackup.upload;

import com.dsh.worldbackup.compress.ProgressCallback;
import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.util.BackupException;
import com.dsh.worldbackup.util.CancelledException;
import com.dsh.worldbackup.util.FileUtil;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebDAV 上传实现，基于 JDK 自带 HttpClient，无第三方依赖。
 * 兼容坚果云、Nextcloud、ownCloud、Alist、群晖、nginx-dav 等标准 WebDAV 服务。
 */
public final class WebDavUploader implements Uploader {

    private static final String PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8"?>
            <d:propfind xmlns:d="DAV:"><d:prop>
            <d:getlastmodified/><d:getcontentlength/><d:resourcetype/>
            </d:prop></d:propfind>""";

    private static final Pattern RESPONSE_PATTERN =
            Pattern.compile("<(?:\\w+:)?response\\b.*?</(?:\\w+:)?response>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern HREF_PATTERN =
            Pattern.compile("<(?:\\w+:)?href\\b[^>]*>(.*?)</(?:\\w+:)?href>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern LASTMODIFIED_PATTERN =
            Pattern.compile("<(?:\\w+:)?getlastmodified\\b[^>]*>(.*?)</(?:\\w+:)?getlastmodified>",
                    Pattern.DOTALL | Pattern.CASE_INSENSITIVE);
    private static final Pattern COLLECTION_PATTERN =
            Pattern.compile("<(?:\\w+:)?collection\\s*/?>", Pattern.CASE_INSENSITIVE);

    private final PluginSettings settings;
    private final Logger log;
    private final URI baseUri;
    private final String authorization;
    private final HttpClient client;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile boolean directoriesReady;

    public WebDavUploader(PluginSettings settings, Logger log) {
        this.settings = settings;
        this.log = log;
        this.baseUri = buildBaseUri(settings.uploadUrl());
        this.authorization = settings.uploadUsername().isBlank()
                ? null
                : "Basic " + Base64.getEncoder().encodeToString(
                        (settings.uploadUsername() + ":" + settings.uploadPassword()).getBytes(StandardCharsets.UTF_8));

        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(settings.connectTimeoutSeconds()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1);
        if (settings.allowInsecureTls()) {
            configureInsecureTls(builder);
        }
        this.client = builder.build();
    }

    @Override
    public UploadResult upload(Path file, String remoteName, ProgressCallback callback) throws Exception {
        if (cancelled.get()) {
            throw new CancelledException("上传已取消");
        }
        long total = Files.size(file);
        ensureCollections();

        URI target = buildTargetUri(remoteName);
        int attempts = settings.retryCount() + 1;
        Exception lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (cancelled.get()) {
                throw new CancelledException("上传已取消");
            }
            try {
                if (attempt > 1 && log != null) {
                    log.info("上传重试 " + attempt + "/" + attempts + "：" + remoteName);
                }
                return doUpload(target, file, total, callback);
            } catch (UploadAuthException e) {
                throw e;
            } catch (CancelledException e) {
                throw e;
            } catch (Exception e) {
                lastError = e;
                if (log != null) {
                    log.warning("上传失败（第 " + attempt + " 次）：" + e.getMessage());
                }
                if (attempt < attempts) {
                    sleepSeconds(settings.retryDelaySeconds());
                }
            }
        }
        throw new BackupException("上传到网盘失败：" + (lastError == null ? "未知错误" : lastError.getMessage()), lastError);
    }

    private UploadResult doUpload(URI target, Path file, long total, ProgressCallback callback) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ZHMBackup-upload");
            thread.setDaemon(true);
            return thread;
        });
        try {
            ProgressBodyPublisher publisher = new ProgressBodyPublisher(file, sent -> {
                if (callback != null) {
                    double ratio = total > 0 ? Math.min(1.0, (double) sent / (double) total) : 0.0;
                    callback.onProgress(sent, total, ratio);
                }
            }, executor, cancelled);

            HttpRequest.Builder builder = HttpRequest.newBuilder(target)
                    .timeout(Duration.ofMinutes(settings.requestTimeoutMinutes()))
                    .header("Content-Type", "application/octet-stream")
                    .header("User-Agent", "ZHM-WorldBackup-Helper/1.0.1 (+paper-plugin)")
                    .PUT(publisher);
            if (authorization != null) {
                builder.header("Authorization", authorization);
            }

            long start = System.currentTimeMillis();
            HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            long elapsed = System.currentTimeMillis() - start;

            if (code >= 200 && code < 300) {
                if (callback != null) {
                    callback.onProgress(total, total, 1.0);
                }
                return new UploadResult(true, target.toString(), total, elapsed, "HTTP " + code);
            }
            if (code == 401 || code == 403) {
                throw new UploadAuthException("网盘认证失败（HTTP " + code + "），请检查 upload.username / upload.password");
            }
            if (code == 507) {
                throw new BackupException("网盘空间不足（HTTP 507）");
            }
            if (code == 409) {
                throw new BackupException("网盘目录不存在且无法创建（HTTP 409），请检查 upload.url");
            }
            // 4xx 客户端错误不重试
            if (code >= 400 && code < 500) {
                throw new BackupException("网盘拒绝上传（HTTP " + code + "）" + bodyHint(response.body()));
            }
            throw new IOException("HTTP " + code + bodyHint(response.body()));
        } finally {
            executor.shutdownNow();
        }
    }

    private static String bodyHint(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String trimmed = body.strip().replaceAll("\\s+", " ");
        return "：" + (trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed);
    }

    /** 逐级创建远端目录（MKCOL），已存在的目录会被忽略。 */
    private void ensureCollections() throws Exception {
        if (directoriesReady) {
            return;
        }
        String rawPath = baseUri.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) {
            directoriesReady = true;
            return;
        }
        int index = 0;
        while ((index = rawPath.indexOf('/', index + 1)) >= 0) {
            String subPath = rawPath.substring(0, index + 1);
            if (subPath.equals("/")) {
                continue;
            }
            URI directory = URI.create(baseUri.getScheme() + "://" + baseUri.getRawAuthority() + subPath);
            mkcol(directory);
        }
        directoriesReady = true;
    }

    private void mkcol(URI directory) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(directory)
                .timeout(Duration.ofSeconds(Math.max(30, settings.connectTimeoutSeconds())))
                .method("MKCOL", HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        switch (code) {
            case 200, 201, 204 -> log.fine("已创建网盘目录：" + directory);
            // 405 = 已存在；301/302 = 目录已存在（服务端补斜杠）；403 常见于坚果云已存在目录
            case 301, 302, 405, 403 -> log.fine("网盘目录已存在：" + directory);
            case 401 -> throw new UploadAuthException("网盘认证失败（HTTP 401），请检查 upload.username / upload.password");
            case 409 -> throw new BackupException("无法创建网盘目录（HTTP 409），请确认上级目录存在：" + directory);
            default -> {
                if (code >= 500) {
                    throw new BackupException("创建网盘目录失败（HTTP " + code + "）：" + directory);
                }
                log.fine("创建网盘目录返回 HTTP " + code + "：" + directory);
            }
        }
    }

    @Override
    public int cleanupRemote(String namePattern, int keepCount) throws Exception {
        if (keepCount <= 0) {
            return 0;
        }
        List<RemoteEntry> files = listRemote();
        List<RemoteEntry> matched = new ArrayList<>();
        for (RemoteEntry entry : files) {
            if (FileUtil.matches(namePattern, entry.name())) {
                matched.add(entry);
            }
        }
        matched.sort(Comparator.comparingLong(RemoteEntry::lastModified).reversed()
                .thenComparing(RemoteEntry::name, Comparator.reverseOrder()));

        int deleted = 0;
        for (int i = keepCount; i < matched.size(); i++) {
            if (cancelled.get()) {
                throw new CancelledException("清理已取消");
            }
            RemoteEntry entry = matched.get(i);
            if (delete(entry.uri())) {
                deleted++;
                if (log != null) {
                    log.info("已删除网盘旧备份：" + entry.name());
                }
            }
        }
        return deleted;
    }

    private List<RemoteEntry> listRemote() throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(baseUri)
                .timeout(Duration.ofMinutes(2))
                .header("Depth", "1")
                .header("Content-Type", "application/xml; charset=utf-8")
                .method("PROPFIND", HttpRequest.BodyPublishers.ofString(PROPFIND_BODY, StandardCharsets.UTF_8));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code == 401 || code == 403) {
            throw new UploadAuthException("网盘认证失败（HTTP " + code + "），无法列出远端备份");
        }
        if (code != 207 && code != 200) {
            throw new BackupException("无法列出网盘文件（HTTP " + code + "）" + bodyHint(response.body()));
        }

        List<RemoteEntry> entries = new ArrayList<>();
        Matcher responseMatcher = RESPONSE_PATTERN.matcher(response.body());
        while (responseMatcher.find()) {
            String block = responseMatcher.group();
            Matcher hrefMatcher = HREF_PATTERN.matcher(block);
            if (!hrefMatcher.find()) {
                continue;
            }
            String href = hrefMatcher.group(1).strip();
            if (COLLECTION_PATTERN.matcher(block).find()) {
                continue;
            }
            String name = decodeName(href);
            if (name.isEmpty()) {
                continue;
            }
            long lastModified = 0L;
            Matcher modifiedMatcher = LASTMODIFIED_PATTERN.matcher(block);
            if (modifiedMatcher.find()) {
                lastModified = parseHttpDate(modifiedMatcher.group(1).strip());
            }
            entries.add(new RemoteEntry(name, resolveHref(href), lastModified));
        }
        if (log != null) {
            log.info("网盘上共有 " + entries.size() + " 个备份文件");
        }
        return entries;
    }

    private boolean delete(URI uri) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(2))
                .method("DELETE", HttpRequest.BodyPublishers.noBody());
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        int code = response.statusCode();
        if (code >= 200 && code < 300) {
            return true;
        }
        if (log != null) {
            log.warning("删除网盘文件失败（HTTP " + code + "）：" + uri);
        }
        return false;
    }

    private URI resolveHref(String href) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return URI.create(href);
        }
        return URI.create(baseUri.getScheme() + "://" + baseUri.getRawAuthority()
                + (href.startsWith("/") ? href : "/" + href));
    }

    private static String decodeName(String href) {
        String path = href;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return name;
        }
    }

    private static long parseHttpDate(String value) {
        try {
            return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (Exception ignored) {
            // 部分服务端返回 ISO-8601
        }
        try {
            return Instant.parse(value).toEpochMilli();
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private URI buildTargetUri(String remoteName) {
        return URI.create(baseUri.toString() + encodeSegment(remoteName));
    }

    private static String encodeSegment(String name) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** 解析用户填写的地址，兼容中文路径（会自动百分号编码）。 */
    private static URI buildBaseUri(String raw) {
        String url = raw == null ? "" : raw.strip();
        if (url.isEmpty()) {
            throw new BackupException("upload.url 未配置");
        }
        if (!url.endsWith("/")) {
            url = url + "/";
        }
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            // 含中文/空格等非法字符时手动拆分并交由 URI 构造函数编码
            try {
                int schemeEnd = url.indexOf("://");
                if (schemeEnd < 0) {
                    throw new BackupException("upload.url 格式不正确：" + raw);
                }
                String scheme = url.substring(0, schemeEnd);
                String rest = url.substring(schemeEnd + 3);
                int slash = rest.indexOf('/');
                String authority = slash < 0 ? rest : rest.substring(0, slash);
                String path = slash < 0 ? "/" : rest.substring(slash);
                return new URI(scheme, authority, path, null, null);
            } catch (URISyntaxException ex) {
                throw new BackupException("upload.url 格式不正确：" + raw, ex);
            }
        }
    }

    private void sleepSeconds(int seconds) {
        if (seconds <= 0) {
            return;
        }
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancelledException("上传已取消");
        }
    }

    private static void configureInsecureTls(HttpClient.Builder builder) {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            SSLParameters parameters = new SSLParameters();
            parameters.setEndpointIdentificationAlgorithm(null);
            builder.sslContext(context).sslParameters(parameters);
        } catch (Exception e) {
            throw new BackupException("无法启用 allow-insecure-tls：" + e.getMessage(), e);
        }
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }

    @Override
    public String describe() {
        return "WebDAV " + baseUri;
    }

    /** 远端文件条目。 */
    private record RemoteEntry(String name, URI uri, long lastModified) {
    }
}
