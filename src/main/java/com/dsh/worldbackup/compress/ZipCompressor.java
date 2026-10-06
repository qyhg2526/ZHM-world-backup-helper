package com.dsh.worldbackup.compress;

import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.util.CancelledException;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * JDK 自带 zip 压缩，作为最后兜底方案（体积最大，但零依赖）。
 */
public final class ZipCompressor implements Compressor {

    private final PluginSettings settings;
    private final Logger log;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public ZipCompressor(PluginSettings settings, Logger log) {
        this.settings = settings;
        this.log = log;
    }

    @Override
    public String displayName() {
        return "ZIP (JDK Deflate)";
    }

    @Override
    public String extension() {
        return ".zip";
    }

    @Override
    public CompressionResult compress(SourceSet set, Path archive, ProgressCallback callback) throws Exception {
        Files.createDirectories(archive.getParent());
        Files.deleteIfExists(archive);

        long start = System.currentTimeMillis();
        long total = set.totalBytes();
        long processed = 0L;
        long lastReport = 0L;
        byte[] buffer = new byte[1 << 16];

        try (OutputStream fileOut = Files.newOutputStream(archive);
             ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(fileOut, 1 << 16),
                     StandardCharsets.UTF_8)) {
            zip.setLevel(Math.max(Deflater.DEFAULT_COMPRESSION, settings.level()));

            for (SourceEntry entry : set.entries()) {
                for (SourceSet.TreeItem item : set.items(entry)) {
                    if (cancelled.get()) {
                        throw new CancelledException("备份已被管理员取消");
                    }
                    if (!Files.exists(item.path())) {
                        continue;
                    }
                    try {
                        if (item.directory()) {
                            zip.putNextEntry(new ZipEntry(item.archivePath() + "/"));
                            zip.closeEntry();
                        } else {
                            ZipEntry zipEntry = new ZipEntry(item.archivePath());
                            zipEntry.setLastModifiedTime(Files.getLastModifiedTime(item.path()));
                            zip.putNextEntry(zipEntry);
                            try (InputStream in = Files.newInputStream(item.path())) {
                                int read;
                                while ((read = in.read(buffer)) > 0) {
                                    if (cancelled.get()) {
                                        throw new CancelledException("备份已被管理员取消");
                                    }
                                    zip.write(buffer, 0, read);
                                    processed += read;
                                    long now = System.currentTimeMillis();
                                    if (now - lastReport >= 200L) {
                                        lastReport = now;
                                        callback.onProgress(processed, total, ratio(processed, total));
                                    }
                                }
                            } catch (NoSuchFileException e) {
                                if (log != null) {
                                    log.fine("文件在备份过程中消失，已跳过：" + item.path());
                                }
                            }
                            zip.closeEntry();
                        }
                    } catch (NoSuchFileException e) {
                        if (log != null) {
                            log.fine("文件在备份过程中消失，已跳过：" + item.path());
                        }
                    }
                    callback.onProgress(processed, total, ratio(processed, total));
                }
            }
        }

        long elapsed = System.currentTimeMillis() - start;
        callback.onProgress(total, total, 1.0);
        return new CompressionResult(archive, Files.size(archive), total, set.totalFiles(), elapsed, displayName());
    }

    private static double ratio(long processed, long total) {
        if (total <= 0L) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, (double) processed / (double) total));
    }

    @Override
    public void cancel() {
        cancelled.set(true);
    }
}
