package com.aqishi.toolbox.feature.system.domain;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取压缩包条目的防御性工具：有上限的读取、压缩比守卫、条目路径归一。
 *
 * <p>JAR 是用户随手拖进来的任意文件，可能是 zip 炸弹：几 KB 的条目解压出几 GB。
 * 这里所有读取都有字节上限；当已解压字节超过 {@link #RATIO_CHECK_THRESHOLD} 后，
 * 再按“解压字节 / 压缩字节”检查压缩比，超限即中止——小文件的压缩比天然可能很高（全是空格的文本），
 * 所以只对大条目生效。</p>
 */
public final class ArchiveIO {

    /** 解压字节超过这个值才开始检查压缩比。 */
    public static final long RATIO_CHECK_THRESHOLD = 1024 * 1024;

    private static final Pattern VERSIONED = Pattern.compile("^META-INF/versions/(\\d{1,4})/(.+)$");
    private static final String[] CLASS_ROOTS = {"BOOT-INF/classes/", "WEB-INF/classes/"};

    private ArchiveIO() {
    }

    /** 条目超过读取上限。 */
    public static final class EntryTooLargeException extends IOException {
        public EntryTooLargeException(long limit) {
            super("Entry exceeds " + limit + " bytes");
        }
    }

    /** 压缩比超过上限，疑似 zip 炸弹。 */
    public static final class CompressionRatioException extends IOException {
        public CompressionRatioException(long uncompressed, long compressed) {
            super("Compression ratio too high: " + uncompressed + " / " + compressed);
        }
    }

    /**
     * 读取整个条目，但最多 {@code maxBytes} 字节。
     *
     * @param compressed 当前条目迄今消耗的压缩字节数（或声明的压缩大小），未知时返回负数
     * @param maxRatio   允许的最大压缩比；&lt;= 0 表示不检查
     */
    public static byte[] readBounded(InputStream in, int maxBytes, LongSupplier compressed, int maxRatio)
            throws IOException {
        byte[] buffer = new byte[Math.min(maxBytes + 1, 8192)];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        long total = 0;
        int read;
        while ((read = in.read(buffer, 0, buffer.length)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new EntryTooLargeException(maxBytes);
            }
            out.write(buffer, 0, read);
            checkRatio(total, compressed, maxRatio);
        }
        return out.toByteArray();
    }

    /** 读取前 {@code count} 个字节（不足则返回实际读到的部分），不理会条目余下内容。 */
    public static byte[] readPrefix(InputStream in, int count) throws IOException {
        return in.readNBytes(count);
    }

    /**
     * 把条目余下内容读完丢弃，最多 {@code maxBytes} 字节，并做压缩比检查。
     *
     * <p>{@code ZipInputStream.getNextEntry()} 会自己把上一个条目读完——那一步没有任何上限，
     * 嵌套 JAR 里的炸弹条目会在这里无声无息地烧掉 CPU。所以流式遍历时总是先调用本方法。</p>
     *
     * @return 丢弃的字节数
     */
    public static long drain(InputStream in, long maxBytes, LongSupplier compressed, int maxRatio)
            throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new EntryTooLargeException(maxBytes);
            }
            checkRatio(total, compressed, maxRatio);
        }
        return total;
    }

    private static void checkRatio(long uncompressed, LongSupplier compressed, int maxRatio)
            throws CompressionRatioException {
        if (maxRatio <= 0 || uncompressed < RATIO_CHECK_THRESHOLD || compressed == null) {
            return;
        }
        long packed = compressed.getAsLong();
        if (packed >= 0 && uncompressed > (Math.max(packed, 1)) * (long) maxRatio) {
            throw new CompressionRatioException(uncompressed, packed);
        }
    }

    /** 按文件名判断是否是可以当作 zip 打开的 Java 归档。{@code .zip} 只在顶层接受。 */
    public static boolean isArchiveName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") || lower.endsWith(".war") || lower.endsWith(".ear");
    }

    /** 多版本条目 {@code META-INF/versions/N/...} 的 N；不是多版本条目返回 0。 */
    public static int versionOf(String entryName) {
        Matcher matcher = VERSIONED.matcher(entryName);
        if (!matcher.matches()) {
            return 0;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException overflow) {
            return 0;
        }
    }

    /**
     * 去掉类路径根前缀：{@code BOOT-INF/classes/}、{@code WEB-INF/classes/}、{@code META-INF/versions/N/}，
     * 得到类加载器眼中的路径（{@code com/foo/Bar.class}）。
     */
    public static String classPath(String entryName) {
        String name = entryName;
        Matcher matcher = VERSIONED.matcher(name);
        if (matcher.matches()) {
            name = matcher.group(2);
        }
        for (String root : CLASS_ROOTS) {
            if (name.startsWith(root)) {
                return name.substring(root.length());
            }
        }
        return name;
    }

    /** 类条目 → 点分类名（已去掉类路径根前缀）；不是 .class 条目返回 {@code null}。 */
    public static String className(String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        String path = classPath(entryName);
        return path.substring(0, path.length() - ".class".length()).replace('/', '.');
    }

    /** 统计底层流被读取了多少字节，用于流式遍历时估算每个条目消耗的压缩字节。 */
    public static final class CountingInputStream extends FilterInputStream {
        private long count;

        public CountingInputStream(InputStream in) {
            super(in);
        }

        public long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                count++;
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                count += read;
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += Math.max(0, skipped);
            return skipped;
        }
    }

    /**
     * 带字节上限与压缩比守卫的流：超出上限抛 {@link EntryTooLargeException}，
     * 压缩比超限抛 {@link CompressionRatioException}。嵌套 JAR 的原始字节经它读出，
     * 防止一个声明很小、实际巨大的嵌套条目。
     */
    public static final class GuardedInputStream extends FilterInputStream {
        private final long limit;
        private final LongSupplier compressed;
        private final int maxRatio;
        private long count;

        public GuardedInputStream(InputStream in, long limit, LongSupplier compressed, int maxRatio) {
            super(in);
            this.limit = limit;
            this.compressed = compressed;
            this.maxRatio = maxRatio;
        }

        public long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0) {
                advance(1);
            }
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = super.read(b, off, len);
            if (read > 0) {
                advance(read);
            }
            return read;
        }

        @Override
        public long skip(long n) throws IOException {
            // 走 read 以便计数与检查；skip 在 InflaterInputStream 上本来也是解压后丢弃。
            byte[] buffer = new byte[(int) Math.min(8192, Math.max(n, 1))];
            long skipped = 0;
            while (skipped < n) {
                int read = read(buffer, 0, (int) Math.min(buffer.length, n - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        private void advance(int bytes) throws IOException {
            count += bytes;
            if (count > limit) {
                throw new EntryTooLargeException(limit);
            }
            checkRatio(count, compressed, maxRatio);
        }
    }

    // ==========================================
    // 统一的条目遍历：顶层用 ZipFile（随机访问，未读的条目不解压），嵌套用 ZipInputStream
    // ==========================================

    /** 归档中的一个条目。{@link #open()} 返回的流由调用方关闭。 */
    public interface ArchiveEntry {
        String name();

        /** 解压后大小，未知为 -1。 */
        long size();

        /** 压缩后大小，未知为 -1。 */
        long compressedSize();

        boolean directory();

        InputStream open() throws IOException;

        /** 读取过程中本条目已消耗的压缩字节（或声明的压缩大小），供压缩比检查。 */
        LongSupplier compressedCounter();
    }

    /** 条目序列；{@link #next()} 返回 {@code null} 表示结束。 */
    public interface EntrySource extends java.io.Closeable {
        ArchiveEntry next() throws IOException;
    }

    /** 遍历磁盘上的 zip 文件。 */
    public static EntrySource zipFileSource(java.util.zip.ZipFile zip) {
        java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
        return new EntrySource() {
            @Override
            public ArchiveEntry next() {
                if (!entries.hasMoreElements()) {
                    return null;
                }
                java.util.zip.ZipEntry entry = entries.nextElement();
                return new ArchiveEntry() {
                    @Override
                    public String name() {
                        return entry.getName();
                    }

                    @Override
                    public long size() {
                        return entry.getSize();
                    }

                    @Override
                    public long compressedSize() {
                        return entry.getCompressedSize();
                    }

                    @Override
                    public boolean directory() {
                        return entry.isDirectory();
                    }

                    @Override
                    public InputStream open() throws IOException {
                        return zip.getInputStream(entry);
                    }

                    @Override
                    public LongSupplier compressedCounter() {
                        return entry::getCompressedSize;
                    }
                };
            }

            @Override
            public void close() {
                // ZipFile 由创建者关闭
            }
        };
    }

    /**
     * 流式遍历嵌套归档。每次 {@link EntrySource#next()} 前先用 {@link #drain} 把上一个条目读完：
     * 这一步有上限、有压缩比检查，代替 {@code ZipInputStream} 内部无上限的跳过。
     *
     * @param raw         嵌套归档的原始字节流（调用方负责关闭）
     * @param maxEntryBytes 单个条目允许解压的最大字节数
     * @param maxRatio    允许的最大压缩比
     */
    public static EntrySource streamSource(InputStream raw, long maxEntryBytes, int maxRatio) {
        CountingInputStream counting = new CountingInputStream(raw);
        java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(nonClosing(counting));
        return new EntrySource() {
            private boolean pending;
            private long startCount;

            @Override
            public ArchiveEntry next() throws IOException {
                if (pending) {
                    LongSupplier used = () -> counting.count() - startCount;
                    drain(zip, maxEntryBytes, used, maxRatio);
                    pending = false;
                }
                java.util.zip.ZipEntry entry = zip.getNextEntry();
                if (entry == null) {
                    return null;
                }
                pending = true;
                long start = counting.count();
                startCount = start;
                LongSupplier used = () -> counting.count() - start;
                InputStream view = nonClosing(zip);
                return new ArchiveEntry() {
                    @Override
                    public String name() {
                        return entry.getName();
                    }

                    @Override
                    public long size() {
                        return entry.getSize();
                    }

                    @Override
                    public long compressedSize() {
                        return entry.getCompressedSize();
                    }

                    @Override
                    public boolean directory() {
                        return entry.isDirectory();
                    }

                    @Override
                    public InputStream open() {
                        return view;
                    }

                    @Override
                    public LongSupplier compressedCounter() {
                        return used;
                    }
                };
            }

            @Override
            public void close() throws IOException {
                // 只释放 Inflater 的本地内存；原始流经 nonClosing 隔开，由调用方关闭
                zip.close();
            }
        };
    }

    /** 关闭时不关闭底层流：底层是外层 zip 的当前条目，由外层遍历负责推进。 */
    public static InputStream nonClosing(InputStream in) {
        return new FilterInputStream(in) {
            @Override
            public void close() {
                // 故意不关闭
            }
        };
    }
}
