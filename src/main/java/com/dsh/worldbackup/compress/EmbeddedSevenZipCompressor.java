package com.dsh.worldbackup.compress;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.tukaani.xz.LZMA2Options;
import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.util.CancelledException;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * 插件内置的 7z 压缩引擎（Apache Commons Compress + XZ，LZMA2 算法）。
 * 无需在服务器上安装 7-Zip 即可生成 .7z 压缩包。
 * 注意：内置引擎不支持固实压缩，压缩率略低于 7-Zip 命令行。
 */
public final class EmbeddedSevenZipCompressor implements Compressor {

    private final PluginSettings settings;
    private final Logger log;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public EmbeddedSevenZipCompressor(PluginSettings settings, Logger log) {
        this.settings = settings;
        this.log = log;
    }

    @Override
    public String displayName() {
        return "内置 7z 引擎 (LZMA2)";
    }

    @Override
    public String extension() {
        return ".7z";
    }

    @Override
    public CompressionResult compress(SourceSet set, Path archive, ProgressCallback callback) throws Exception {
        Files.createDirectories(archive.getParent());
        Files.deleteIfExists(archive);

        long start = System.currentTimeMillis();
        try {
            writeArchive(set, archive, callback, true);
        } catch (CancelledException e) {
            Files.deleteIfExists(archive);
            throw e;
        } catch (Throwable t) {
            // 个别 commons-compress 版本不接受自定义 LZMA2 预设参数，回退到默认参数
            if (log != null) {
                log.warning("使用 LZMA2 预设参数写入失败（" + t + "），改用默认参数重试");
            }
            Files.deleteIfExists(archive);
            writeArchive(set, archive, callback, false);
        }

        long archiveBytes = Files.size(archive);
        long elapsed = System.currentTimeMillis() - start;
        callback.onProgress(set.totalBytes(), set.totalBytes(), 1.0);
        return new CompressionResult(archive, archiveBytes, set.totalBytes(), set.totalFiles(), elapsed, displayName());
    }

    private void writeArchive(SourceSet set, Path archive, ProgressCallback callback, boolean usePreset) throws Exception {
        byte[] buffer = new byte[1 << 16];
        long total = set.totalBytes();
        long processed = 0L;
        long lastReport = 0L;

        try (SevenZOutputFile out = new SevenZOutputFile(archive.toFile())) {
            if (usePreset) {
                LZMA2Options options = new LZMA2Options(settings.level());
                out.setContentMethods(java.util.List.of(new SevenZMethodConfiguration(SevenZMethod.LZMA2, options)));
            } else {
                out.setContentCompression(SevenZMethod.LZMA2);
            }

            for (SourceEntry entry : set.entries()) {
                for (SourceSet.TreeItem item : set.items(entry)) {
                    if (cancelled.get()) {
                        throw new CancelledException("备份已被管理员取消");
                    }
                    if (!Files.exists(item.path())) {
                        // 世界仍在运行，个别文件可能在扫描后消失
                        continue;
                    }
                    try {
                        SevenZArchiveEntry archiveEntry = out.createArchiveEntry(item.path().toFile(), item.archivePath());
                        if (item.directory()) {
                            archiveEntry.setDirectory(true);
                            archiveEntry.setHasStream(false);
                        }
                        out.putArchiveEntry(archiveEntry);

                        if (!item.directory()) {
                            try (InputStream in = Files.newInputStream(item.path())) {
                                int read;
                                while ((read = in.read(buffer)) > 0) {
                                    if (cancelled.get()) {
                                        throw new CancelledException("备份已被管理员取消");
                                    }
                                    out.write(buffer, 0, read);
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
                        }
                        out.closeArchiveEntry();
                    } catch (NoSuchFileException e) {
                        if (log != null) {
                            log.fine("文件在备份过程中消失，已跳过：" + item.path());
                        }
                    }
                    callback.onProgress(processed, total, ratio(processed, total));
                }
            }
        }
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
