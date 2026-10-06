package com.dsh.worldbackup.compress;

import com.dsh.worldbackup.config.PluginSettings;
import com.dsh.worldbackup.util.BackupException;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * 根据配置选择合适的压缩引擎。
 */
public final class CompressorFactory {

    private CompressorFactory() {
    }

    public static Compressor create(PluginSettings settings, Path workingDir, Logger log) {
        return switch (settings.compressionMode()) {
            case SEVEN_ZIP_CLI -> {
                Path exe = SevenZipLocator.locate(settings.sevenZipPath(), log);
                if (exe == null) {
                    throw new BackupException("compression.mode 指定了 7-Zip 命令行，但没有找到可执行文件，"
                            + "请在 compression.seven-zip-path 中填写完整路径");
                }
                yield new SevenZipCliCompressor(exe, workingDir, settings, log);
            }
            case SEVEN_ZIP_EMBEDDED -> new EmbeddedSevenZipCompressor(settings, log);
            case ZIP -> new ZipCompressor(settings, log);
            case AUTO -> {
                Path exe = SevenZipLocator.locate(settings.sevenZipPath(), log);
                if (exe != null) {
                    yield new SevenZipCliCompressor(exe, workingDir, settings, log);
                }
                if (log != null) {
                    log.info("未检测到系统 7-Zip，改用插件内置 7z(LZMA2) 引擎；"
                            + "安装 7-Zip 后可获得更小的备份体积（固实压缩）");
                }
                yield new EmbeddedSevenZipCompressor(settings, log);
            }
        };
    }
}
