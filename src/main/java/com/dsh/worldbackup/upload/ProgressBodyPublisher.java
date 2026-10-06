package com.dsh.worldbackup.upload;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * 带进度的请求体发布器：按需从文件读取数据块并推送给 HttpClient，
 * 同时报告已发送字节数，使上传过程也能显示进度（并且始终带 Content-Length）。
 */
final class ProgressBodyPublisher implements HttpRequest.BodyPublisher {

    private static final int CHUNK_SIZE = 64 * 1024;

    private final Path file;
    private final long length;
    private final LongConsumer progressListener;
    private final Executor executor;
    private final AtomicBoolean cancelled;
    private final AtomicLong sent = new AtomicLong();

    ProgressBodyPublisher(Path file,
                          LongConsumer progressListener,
                          Executor executor,
                          AtomicBoolean cancelled) throws IOException {
        this.file = file;
        this.progressListener = progressListener;
        this.executor = executor;
        this.cancelled = cancelled;
        this.length = java.nio.file.Files.size(file);
    }

    @Override
    public long contentLength() {
        return length;
    }

    @Override
    public void subscribe(Flow.Subscriber<? super ByteBuffer> subscriber) {
        subscriber.onSubscribe(new Subscription(subscriber));
    }

    private final class Subscription implements Flow.Subscription {

        private final Flow.Subscriber<? super ByteBuffer> subscriber;
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean finished = new AtomicBoolean();
        private final AtomicBoolean draining = new AtomicBoolean();
        private FileChannel channel;

        private Subscription(Flow.Subscriber<? super ByteBuffer> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void request(long n) {
            if (n <= 0) {
                cancel();
                return;
            }
            demand.addAndGet(n);
            scheduleDrain();
        }

        @Override
        public void cancel() {
            if (finished.compareAndSet(false, true)) {
                closeChannelQuietly();
            }
        }

        private void scheduleDrain() {
            executor.execute(() -> {
                if (draining.compareAndSet(false, true)) {
                    try {
                        drain();
                    } finally {
                        draining.set(false);
                    }
                }
            });
        }

        private void drain() {
            if (finished.get() || cancelled.get()) {
                cancel();
                return;
            }
            try {
                if (channel == null) {
                    channel = FileChannel.open(file, StandardOpenOption.READ);
                }
                while (!finished.get() && !cancelled.get() && demand.get() > 0) {
                    // 每块都必须新建缓冲区：HttpClient 会将 ByteBuffer 放入队列后异步写出，
                    // 复用同一个缓冲区会导致数据错乱甚至上传卡死。
                    ByteBuffer buffer = ByteBuffer.allocate(CHUNK_SIZE);
                    int read = channel.read(buffer);
                    if (read < 0) {
                        if (finished.compareAndSet(false, true)) {
                            closeChannelQuietly();
                            subscriber.onComplete();
                        }
                        return;
                    }
                    if (read == 0) {
                        continue;
                    }
                    buffer.flip();
                    demand.decrementAndGet();
                    long total = sent.addAndGet(read);
                    if (progressListener != null) {
                        progressListener.accept(total);
                    }
                    subscriber.onNext(buffer);
                }
                if (cancelled.get()) {
                    cancel();
                }
            } catch (Throwable t) {
                if (finished.compareAndSet(false, true)) {
                    closeChannelQuietly();
                    subscriber.onError(t);
                }
            }
        }

        private void closeChannelQuietly() {
            try {
                if (channel != null) {
                    channel.close();
                }
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }
}
