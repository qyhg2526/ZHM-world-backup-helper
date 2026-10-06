package com.dsh.worldbackup.upload;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 独立探针：直接使用 ProgressBodyPublisher 上传一个大文件，
 * 用于在不启动 Minecraft 服务端的情况下验证上传进度与稳定性。
 *
 * 用法：java -cp <dist.jar> com.dsh.worldbackup.upload.UploadProbe <file> <url>
 */
public final class UploadProbe {

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        URI target = URI.create(args[1]);
        long total = Files.size(file);
        System.out.println("上传 " + file + " (" + total + " 字节) -> " + target);

        ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "probe-upload");
            t.setDaemon(true);
            return t;
        });
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicLong lastPrint = new AtomicLong();

        ProgressBodyPublisher publisher = new ProgressBodyPublisher(file, sent -> {
            if (sent - lastPrint.get() >= 20L * 1024 * 1024) {
                lastPrint.set(sent);
                System.out.printf("  已发送 %d / %d (%.1f%%)%n", sent, total, sent * 100.0 / total);
            }
        }, executor, cancelled);

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        long start = System.currentTimeMillis();
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(target)
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/octet-stream")
                .PUT(publisher);
        if (args.length > 2) {
            String credentials = args[2] + ":" + (args.length > 3 ? args[3] : "");
            requestBuilder.header("Authorization", "Basic " + java.util.Base64.getEncoder()
                    .encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        HttpResponse<String> response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
        long elapsed = System.currentTimeMillis() - start;
        System.out.println("HTTP " + response.statusCode() + " 用时 " + elapsed + " ms");
        System.out.printf("平均速度 %.1f MB/s%n", total / 1024.0 / 1024.0 / (elapsed / 1000.0));
        executor.shutdownNow();
    }
}
