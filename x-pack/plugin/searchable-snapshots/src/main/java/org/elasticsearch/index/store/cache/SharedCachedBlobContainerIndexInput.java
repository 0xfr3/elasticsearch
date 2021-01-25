/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */

package org.elasticsearch.index.store.cache;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.BytesRefIterator;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.blobstore.cache.BlobStoreCacheService;
import org.elasticsearch.blobstore.cache.CachedBlob;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.io.Channels;
import org.elasticsearch.common.lease.Releasable;
import org.elasticsearch.common.unit.ByteSizeUnit;
import org.elasticsearch.index.snapshots.blobstore.BlobStoreIndexShardSnapshot.FileInfo;
import org.elasticsearch.index.snapshots.blobstore.SlicedInputStream;
import org.elasticsearch.index.store.BaseSearchableSnapshotIndexInput;
import org.elasticsearch.index.store.IndexInputStats;
import org.elasticsearch.index.store.SearchableSnapshotDirectory;
import org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshotsConstants;
import org.elasticsearch.xpack.searchablesnapshots.cache.SearchableSnapshotsLFUCache.SharedCacheFile;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.elasticsearch.index.store.checksum.ChecksumBlobContainerIndexInput.checksumToBytesArray;
import static org.elasticsearch.xpack.searchablesnapshots.SearchableSnapshotsUtils.toIntBytes;

public class SharedCachedBlobContainerIndexInput extends BaseSearchableSnapshotIndexInput {

    public static final IOContext CACHE_WARMING_CONTEXT = new IOContext();

    private static final Logger logger = LogManager.getLogger(SharedCachedBlobContainerIndexInput.class);
    private static final int COPY_BUFFER_SIZE = ByteSizeUnit.KB.toIntBytes(8);

    private final SearchableSnapshotDirectory directory;
    private final SharedCacheFile sharedCacheFile;
    private final int defaultRangeSize;
    private final int recoveryRangeSize;

    // last read position is kept around in order to detect (non)contiguous reads for stats
    private long lastReadPosition;
    // last seek position is kept around in order to detect forward/backward seeks for stats
    private long lastSeekPosition;

    public SharedCachedBlobContainerIndexInput(
        SearchableSnapshotDirectory directory,
        FileInfo fileInfo,
        IOContext context,
        IndexInputStats stats,
        int rangeSize,
        int recoveryRangeSize
    ) {
        this(
            "SharedCachedBlobContainerIndexInput(" + fileInfo.physicalName() + ")",
            directory,
            fileInfo,
            context,
            stats,
            0L,
            fileInfo.length(),
            directory.getSharedCacheFile(fileInfo.physicalName(), fileInfo.length()),
            rangeSize,
            recoveryRangeSize
        );
        assert getBufferSize() <= BlobStoreCacheService.DEFAULT_CACHED_BLOB_SIZE; // must be able to cache at least one buffer's worth
        stats.incrementOpenCount();
    }

    private SharedCachedBlobContainerIndexInput(
        String resourceDesc,
        SearchableSnapshotDirectory directory,
        FileInfo fileInfo,
        IOContext context,
        IndexInputStats stats,
        long offset,
        long length,
        SharedCacheFile sharedCacheFile,
        int rangeSize,
        int recoveryRangeSize
    ) {
        super(resourceDesc, directory.blobContainer(), fileInfo, context, stats, offset, length);
        this.directory = directory;
        this.sharedCacheFile = sharedCacheFile;
        this.lastReadPosition = this.offset;
        this.lastSeekPosition = this.offset;
        this.defaultRangeSize = rangeSize;
        this.recoveryRangeSize = recoveryRangeSize;
    }

    @Override
    public void innerClose() {
        // nothing needed to be done here
    }

    private void ensureContext(Predicate<IOContext> predicate) throws IOException {
        if (predicate.test(context) == false) {
            assert false : "this method should not be used with this context " + context;
            throw new IOException("Cannot read the index input using context [context=" + context + ", input=" + this + ']');
        }
    }

    private long getDefaultRangeSize() {
        return (context != CACHE_WARMING_CONTEXT)
            ? (directory.isRecoveryFinalized() ? defaultRangeSize : recoveryRangeSize)
            : fileInfo.partSize().getBytes();
    }

    private Tuple<Long, Long> computeRange(long position) {
        final long rangeSize = getDefaultRangeSize();
        long start = (position / rangeSize) * rangeSize;
        long end = Math.min(start + rangeSize, fileInfo.length());
        return Tuple.tuple(start, end);
    }

    @Override
    protected void readInternal(ByteBuffer b) throws IOException {
        ensureContext(ctx -> ctx != CACHE_WARMING_CONTEXT);
        assert assertCurrentThreadIsNotCacheFetchAsync();
        final long position = getFilePointer() + this.offset;
        final int length = b.remaining();

        // We can detect that we're going to read the last 16 bytes (that contains the footer checksum) of the file. Such reads are often
        // executed when opening a Directory and since we have the checksum in the snapshot metadata we can use it to fill the ByteBuffer.
        if (length == CodecUtil.footerLength() && isClone == false && position == fileInfo.length() - length) {
            if (readChecksumFromFileInfo(b)) {
                logger.trace("read footer of file [{}] at position [{}], bypassing all caches", fileInfo.physicalName(), position);
                return;
            }
            assert b.remaining() == length;
        }

        logger.trace("readInternal: read [{}-{}] ([{}] bytes) from [{}]", position, position + length, length, this);

        try {
            // Can we serve the read directly from disk? If so, do so and don't worry about anything else.

            final Future<Integer> waitingForRead = sharedCacheFile.readIfAvailableOrPending(Tuple.tuple(position, position + length),
                (channel, pos, relativePos, len) -> {
                final int read = readCacheFile(channel, pos, relativePos, len, b, position, true);
                assert read <= length : read + " vs " + length;
                return read;
            });

            if (waitingForRead != null) {
                final Integer read = waitingForRead.get();
                assert read == length;
                b.position(read); // mark all bytes as accounted for
                readComplete(position, length);
                return;
            }

            // Requested data is not on disk, so try the cache index next.

            final Tuple<Long, Long> indexCacheMiss; // null if not a miss

            // We try to use the cache index if:
            // - the file is small enough to be fully cached
            // TODO: implement this
            final boolean canBeFullyCached = fileInfo.length() <= BlobStoreCacheService.DEFAULT_CACHED_BLOB_SIZE * 2;
            // - we're reading the first N bytes of the file
            final boolean isStartOfFile = (position + length <= BlobStoreCacheService.DEFAULT_CACHED_BLOB_SIZE);

            if (canBeFullyCached || isStartOfFile) {
                final CachedBlob cachedBlob = directory.getCachedBlob(fileInfo.physicalName(), 0L, length);

                if (cachedBlob == CachedBlob.CACHE_MISS || cachedBlob == CachedBlob.CACHE_NOT_READY) {
                    // We would have liked to find a cached entry but we did not find anything: the cache on the disk will be requested
                    // so we compute the region of the file we would like to have the next time. The region is expressed as a tuple of
                    // {start, end} where positions are relative to the whole file.

                    if (canBeFullyCached) {
                        // if the index input is smaller than twice the size of the blob cache, it will be fully indexed
                        indexCacheMiss = Tuple.tuple(0L, fileInfo.length());
                    } else {
                        // the index input is too large to fully cache, so just cache the initial range
                        indexCacheMiss = Tuple.tuple(0L, (long) BlobStoreCacheService.DEFAULT_CACHED_BLOB_SIZE);
                    }

                    // We must fill in a cache miss even if CACHE_NOT_READY since the cache index is only created on the first put.
                    // TODO TBD use a different trigger for creating the cache index and avoid a put in the CACHE_NOT_READY case.
                } else {
                    logger.trace(
                        "reading [{}] bytes of file [{}] at position [{}] using cache index",
                        length,
                        fileInfo.physicalName(),
                        position
                    );
                    stats.addIndexCacheBytesRead(cachedBlob.length());

                    final BytesRefIterator cachedBytesIterator = cachedBlob.bytes().slice(toIntBytes(position), length).iterator();
                    BytesRef bytesRef;
                    while ((bytesRef = cachedBytesIterator.next()) != null) {
                        b.put(bytesRef.bytes, bytesRef.offset, bytesRef.length);
                    }
                    assert b.position() == length : "copied " + b.position() + " but expected " + length;

                    try {
                        final Tuple<Long, Long> cachedRange = Tuple.tuple(cachedBlob.from(), cachedBlob.to());
                        sharedCacheFile.populateAndRead(
                            cachedRange,
                            cachedRange,
                            (channel, channelPos, relativePos, len) -> cachedBlob.length(),
                            (channel, channelPos, relativePos, len, progressUpdater) -> {
                                assert len == cachedBlob.to() - cachedBlob.from();
                                final long startTimeNanos = stats.currentTimeNanos();
                                final BytesRefIterator iterator = cachedBlob.bytes()
                                    .slice(toIntBytes(relativePos - cachedBlob.from()), toIntBytes(len))
                                    .iterator();
                                long writePosition = channelPos;
                                long bytesCopied = 0L;
                                BytesRef current;
                                while ((current = iterator.next()) != null) {
                                    final ByteBuffer byteBuffer = ByteBuffer.wrap(current.bytes, current.offset, current.length);
                                    while (byteBuffer.remaining() > 0) {
                                        final long bytesWritten = positionalWrite(channel, writePosition, byteBuffer);
                                        bytesCopied += bytesWritten;
                                        writePosition += bytesWritten;
                                        progressUpdater.accept(bytesCopied);
                                    }
                                }
                                long channelTo = channelPos + len;
                                assert writePosition == channelTo : writePosition + " vs " + channelTo;
                                final long endTimeNanos = stats.currentTimeNanos();
                                stats.addCachedBytesWritten(len, endTimeNanos - startTimeNanos);
                                logger.trace("copied bytes [{}-{}] of file [{}] from cache index to disk", relativePos,
                                    relativePos + len, fileInfo);
                            },
                            directory.cacheFetchAsyncExecutor()
                        );
                    } catch (Exception e) {
                        logger.debug(
                            new ParameterizedMessage(
                                "failed to store bytes [{}-{}] of file [{}] obtained from index cache",
                                cachedBlob.from(),
                                cachedBlob.to(),
                                fileInfo
                            ),
                            e
                        );
                        // oh well, no big deal, at least we can return them to the caller.
                    }

                    readComplete(position, length);

                    return;
                }
            } else {
                // requested range is not eligible for caching
                indexCacheMiss = null;
            }

            // Requested data is also not in the cache index, so we must visit the blob store to satisfy both the target range and any
            // miss in the cache index.

            final Tuple<Long, Long> startRangeToWrite = computeRange(position);
            final Tuple<Long, Long> endRangeToWrite = computeRange(position + length - 1);
            assert startRangeToWrite.v2() <= endRangeToWrite.v2() : startRangeToWrite + " vs " + endRangeToWrite;
            final Tuple<Long, Long> rangeToWrite = Tuple.tuple(
                Math.min(startRangeToWrite.v1(), indexCacheMiss == null ? Long.MAX_VALUE : indexCacheMiss.v1()),
                Math.max(endRangeToWrite.v2(), indexCacheMiss == null ? Long.MIN_VALUE : indexCacheMiss.v2())
            );

            assert rangeToWrite.v1() <= position && position + length <= rangeToWrite.v2() : "["
                + position
                + "-"
                + (position + length)
                + "] vs "
                + rangeToWrite;
            final Tuple<Long, Long> rangeToRead = Tuple.tuple(position, position + length);

            final Future<Integer> populateCacheFuture = sharedCacheFile.populateAndRead(rangeToWrite, rangeToRead,
                (channel, pos, relativePos, len) -> {
                return readCacheFile(channel, pos, relativePos, len, b, rangeToRead.v1(), false);
            }, (channel, channelPos, relativePos, l, progressUpdater) -> this.writeCacheFile(channel, channelPos, relativePos, l,
                    rangeToWrite.v1(), progressUpdater), directory.cacheFetchAsyncExecutor());

            if (indexCacheMiss != null) {
                final Releasable onCacheFillComplete = stats.addIndexCacheFill();
                final Future<Integer> readFuture = sharedCacheFile.readIfAvailableOrPending(indexCacheMiss,
                    (channel, channelPos, relativePos, len) -> {
                    // TODO: build up byte buffer until it has all elements
                    //  (register listener on future, and then call putCachedBlob once future is resolved)
                    final int indexCacheMissLength = toIntBytes(indexCacheMiss.v2() - indexCacheMiss.v1());

                    assert len == indexCacheMissLength;

                    // We assume that we only cache small portions of blobs so that we do not need to:
                    // - use a BigArrays for allocation
                    // - use an intermediate copy buffer to read the file in sensibly-sized chunks
                    // - release the buffer once the indexing operation is complete
                    assert indexCacheMissLength <= COPY_BUFFER_SIZE : indexCacheMiss;

                    final ByteBuffer byteBuffer = ByteBuffer.allocate(indexCacheMissLength);
                    Channels.readFromFileChannelWithEofException(channel, channelPos, byteBuffer);
                    // NB use Channels.readFromFileChannelWithEofException not readCacheFile() to avoid counting this in the stats
                    byteBuffer.flip();
                    final BytesReference content = BytesReference.fromByteBuffer(byteBuffer);
                    directory.putCachedBlob(fileInfo.physicalName(), indexCacheMiss.v1(), content, new ActionListener<>() {
                        @Override
                        public void onResponse(Void response) {
                            onCacheFillComplete.close();
                        }

                        @Override
                        public void onFailure(Exception e1) {
                            onCacheFillComplete.close();
                        }
                    });
                    return indexCacheMissLength;
                });

                if (readFuture == null) {
                    // Normally doesn't happen, we're already obtaining a range covering all cache misses above, but theoretically
                    // possible in the case that the real populateAndRead call already failed to obtain this range of the file. In that
                    // case, simply move on.
                    onCacheFillComplete.close();
                }
            }

            final int bytesRead = populateCacheFuture.get();
            assert bytesRead == length : bytesRead + " vs " + length;
            b.position(bytesRead); // mark all bytes as accounted for
        } catch (final Exception e) {
            // may have partially filled the buffer before the exception was thrown, so try and get the remainder directly.
            final int alreadyRead = length - b.remaining();
            final int bytesRead = readDirectlyIfAlreadyClosed(position + alreadyRead, b, e);
            assert alreadyRead + bytesRead == length : alreadyRead + " + " + bytesRead + " vs " + length;

            // In principle we could handle an index cache miss here too, ensuring that the direct read was large enough, but this is
            // already a rare case caused by an overfull/undersized cache.
        }

        readComplete(position, length);
    }

    private void readComplete(long position, int length) {
        stats.incrementBytesRead(lastReadPosition, position, length);
        lastReadPosition = position + length;
        lastSeekPosition = lastReadPosition;
    }

    private int readDirectlyIfAlreadyClosed(long position, ByteBuffer b, Exception e) throws IOException {
        if (e instanceof AlreadyClosedException || (e.getCause() != null && e.getCause() instanceof AlreadyClosedException)) {
            try {
                // cache file was evicted during the range fetching, read bytes directly from blob container
                final long length = b.remaining();
                final byte[] copyBuffer = new byte[toIntBytes(Math.min(COPY_BUFFER_SIZE, length))];
                logger.trace(
                    () -> new ParameterizedMessage(
                        "direct reading of range [{}-{}] for cache file [{}]",
                        position,
                        position + length,
                        sharedCacheFile
                    )
                );

                int bytesCopied = 0;
                final long startTimeNanos = stats.currentTimeNanos();
                try (InputStream input = openInputStreamFromBlobStore(position, length)) {
                    long remaining = length;
                    while (remaining > 0) {
                        final int len = (remaining < copyBuffer.length) ? (int) remaining : copyBuffer.length;
                        int bytesRead = input.read(copyBuffer, 0, len);
                        if (bytesRead == -1) {
                            throw new EOFException(
                                String.format(
                                    Locale.ROOT,
                                    "unexpected EOF reading [%d-%d] ([%d] bytes remaining) from %s",
                                    position,
                                    position + length,
                                    remaining,
                                    sharedCacheFile
                                )
                            );
                        }
                        b.put(copyBuffer, 0, bytesRead);
                        bytesCopied += bytesRead;
                        remaining -= bytesRead;
                        assert remaining == b.remaining() : remaining + " vs " + b.remaining();
                    }
                    final long endTimeNanos = stats.currentTimeNanos();
                    stats.addDirectBytesRead(bytesCopied, endTimeNanos - startTimeNanos);
                }
                return bytesCopied;
            } catch (Exception inner) {
                e.addSuppressed(inner);
            }
        }
        throw new IOException("failed to read data from cache", e);
    }

    private boolean readChecksumFromFileInfo(ByteBuffer b) throws IOException {
        assert isClone == false;
        byte[] footer;
        try {
            footer = checksumToBytesArray(fileInfo.checksum());
        } catch (NumberFormatException e) {
            // tests disable this optimisation by passing an invalid checksum
            footer = null;
        }
        if (footer == null) {
            return false;
        }

        b.put(footer);
        assert b.remaining() == 0L;
        return true;

        // TODO we should add this to DirectBlobContainerIndexInput too.
    }

    @SuppressForbidden(reason = "Use positional writes on purpose")
    private static int positionalWrite(FileChannel fc, long start, ByteBuffer byteBuffer) throws IOException {
        assert assertCurrentThreadMayWriteCacheFile();
        return fc.write(byteBuffer, start);
    }

    /**
     * Perform a single {@code read()} from {@code inputStream} into {@code copyBuffer}, handling an EOF by throwing an {@link EOFException}
     * rather than returning {@code -1}. Returns the number of bytes read, which is always positive.
     *
     * Most of its arguments are there simply to make the message of the {@link EOFException} more informative.
     */
    private static int readSafe(
        InputStream inputStream,
        byte[] copyBuffer,
        long rangeStart,
        long rangeEnd,
        long remaining,
        SharedCacheFile sharedCacheFile
    ) throws IOException {
        final int len = (remaining < copyBuffer.length) ? toIntBytes(remaining) : copyBuffer.length;
        final int bytesRead = inputStream.read(copyBuffer, 0, len);
        if (bytesRead == -1) {
            throw new EOFException(
                String.format(
                    Locale.ROOT,
                    "unexpected EOF reading [%d-%d] ([%d] bytes remaining) from %s",
                    rangeStart,
                    rangeEnd,
                    remaining,
                    sharedCacheFile
                )
            );
        }
        assert bytesRead > 0 : bytesRead;
        return bytesRead;
    }

    /**
     * Asserts that the range of bytes to warm in cache is aligned with {@link #fileInfo}'s part size.
     */
    private boolean assertRangeIsAlignedWithPart(Tuple<Long, Long> range) {
        if (fileInfo.numberOfParts() == 1L) {
            final long length = fileInfo.length();
            assert range.v1() == 0L : "start of range [" + range.v1() + "] is not aligned with zero";
            assert range.v2() == length : "end of range [" + range.v2() + "] is not aligned with file length [" + length + ']';
        } else {
            final long length = fileInfo.partSize().getBytes();
            assert range.v1() % length == 0L : "start of range [" + range.v1() + "] is not aligned with part start";
            assert range.v2() % length == 0L || (range.v2() == fileInfo.length()) : "end of range ["
                + range.v2()
                + "] is not aligned with part end or with file length";
        }
        return true;
    }

    private int readCacheFile(final FileChannel fc, long channelPos, long relativePos, long length, final ByteBuffer buffer,
                              long logicalPos, boolean cached)
        throws IOException {
        assert assertFileChannelOpen(fc);
        //logger.info("{}: reading cached {} logical {} channel {} pos {} length {} (details: {})", fileInfo.physicalName(), cached,
        //    logicalPos, channelPos, relativePos, length, sharedCacheFile);
        if (length == 0L) {
            return 0;
        }
        // create slice that is positioned to read the given values
        ByteBuffer dup = buffer.duplicate();
        final int newPosition = dup.position() + Math.toIntExact(relativePos);
        assert newPosition <= dup.limit();
        dup.position(newPosition);
        dup.limit(newPosition + Math.toIntExact(length));
        final int bytesRead = Channels.readFromFileChannel(fc, channelPos, dup);
        if (bytesRead == -1) {
            throw new EOFException(
                String.format(
                    Locale.ROOT,
                    "unexpected EOF reading [%d-%d] from %s",
                    channelPos,
                    channelPos + dup.remaining(),
                    this.sharedCacheFile
                )
            );
        }
        stats.addCachedBytesRead(bytesRead);
        return bytesRead;
    }

    private void writeCacheFile(final FileChannel fc, long fileChannelPos, long relativePos, long length, long logicalPos,
                                final Consumer<Long> progressUpdater)
        throws IOException {
        assert assertFileChannelOpen(fc);
        assert assertCurrentThreadMayWriteCacheFile();
        //logger.info("{}: writing logical {} channel {} pos {} length {} (details: {})", fileInfo.physicalName(), logicalPos,
        //    fileChannelPos, relativePos, length, sharedCacheFile);
        final long end = relativePos + length;
        final byte[] copyBuffer = new byte[toIntBytes(Math.min(COPY_BUFFER_SIZE, length))];
        logger.trace(() -> new ParameterizedMessage("writing range [{}-{}] to cache file [{}]", relativePos,
            end, sharedCacheFile));

        long bytesCopied = 0L;
        long remaining = length;
        final long startTimeNanos = stats.currentTimeNanos();
        try (InputStream input = openInputStreamFromBlobStore(logicalPos + relativePos, length)) {
            while (remaining > 0L) {
                final int bytesRead = readSafe(input, copyBuffer, relativePos, end, remaining, sharedCacheFile);
                positionalWrite(fc, fileChannelPos + bytesCopied, ByteBuffer.wrap(copyBuffer, 0, bytesRead));
                bytesCopied += bytesRead;
                remaining -= bytesRead;
                progressUpdater.accept(bytesCopied);
            }
            final long endTimeNanos = stats.currentTimeNanos();
            stats.addCachedBytesWritten(bytesCopied, endTimeNanos - startTimeNanos);
        }
    }

    /**
     * Opens an {@link InputStream} for the given range of bytes which reads the data directly from the blob store. If the requested range
     * spans multiple blobs then this stream will request them in turn.
     *
     * @param position The start of the range of bytes to read, relative to the start of the corresponding Lucene file.
     * @param length The number of bytes to read
     */
    private InputStream openInputStreamFromBlobStore(final long position, final long length) throws IOException {
        assert assertCurrentThreadMayAccessBlobStore();
        if (fileInfo.numberOfParts() == 1L) {
            assert position + length <= fileInfo.partBytes(0) : "cannot read ["
                + position
                + "-"
                + (position + length)
                + "] from ["
                + fileInfo
                + "]";
            stats.addBlobStoreBytesRequested(length);
            return blobContainer.readBlob(fileInfo.partName(0), position, length);
        } else {
            final int startPart = getPartNumberForPosition(position);
            final int endPart = getPartNumberForPosition(position + length - 1);

            for (int currentPart = startPart; currentPart <= endPart; currentPart++) {
                final long startInPart = (currentPart == startPart) ? getRelativePositionInPart(position) : 0L;
                final long endInPart = (currentPart == endPart)
                    ? getRelativePositionInPart(position + length - 1) + 1
                    : getLengthOfPart(currentPart);
                stats.addBlobStoreBytesRequested(endInPart - startInPart);
            }

            return new SlicedInputStream(endPart - startPart + 1) {
                @Override
                protected InputStream openSlice(int slice) throws IOException {
                    final int currentPart = startPart + slice;
                    final long startInPart = (currentPart == startPart) ? getRelativePositionInPart(position) : 0L;
                    final long endInPart = (currentPart == endPart)
                        ? getRelativePositionInPart(position + length - 1) + 1
                        : getLengthOfPart(currentPart);
                    return blobContainer.readBlob(fileInfo.partName(currentPart), startInPart, endInPart - startInPart);
                }
            };
        }
    }

    /**
     * Compute the part number that contains the byte at the given position in the corresponding Lucene file.
     */
    private int getPartNumberForPosition(long position) {
        ensureValidPosition(position);
        final int part = Math.toIntExact(position / fileInfo.partSize().getBytes());
        assert part <= fileInfo.numberOfParts() : "part number [" + part + "] exceeds number of parts: " + fileInfo.numberOfParts();
        assert part >= 0 : "part number [" + part + "] is negative";
        return part;
    }

    /**
     * Compute the position of the given byte relative to the start of its part.
     * @param position the position of the required byte (within the corresponding Lucene file)
     */
    private long getRelativePositionInPart(long position) {
        ensureValidPosition(position);
        final long pos = position % fileInfo.partSize().getBytes();
        assert pos < fileInfo.partBytes(getPartNumberForPosition(pos)) : "position in part [" + pos + "] exceeds part's length";
        assert pos >= 0L : "position in part [" + pos + "] is negative";
        return pos;
    }

    private long getLengthOfPart(int part) {
        return fileInfo.partBytes(part);
    }

    private void ensureValidPosition(long position) {
        assert position >= 0L && position < fileInfo.length() : position + " vs " + fileInfo.length();
        // noinspection ConstantConditions in case assertions are disabled
        if (position < 0L || position >= fileInfo.length()) {
            throw new IllegalArgumentException("Position [" + position + "] is invalid for a file of length [" + fileInfo.length() + "]");
        }
    }

    @Override
    protected void seekInternal(long pos) throws IOException {
        if (pos > length()) {
            throw new EOFException("Reading past end of file [position=" + pos + ", length=" + length() + "] for " + toString());
        } else if (pos < 0L) {
            throw new IOException("Seeking to negative position [" + pos + "] for " + toString());
        }
        final long position = pos + this.offset;
        stats.incrementSeeks(lastSeekPosition, position);
        lastSeekPosition = position;
    }

    @Override
    public SharedCachedBlobContainerIndexInput clone() {
        return (SharedCachedBlobContainerIndexInput) super.clone();
    }

    @Override
    public IndexInput slice(String sliceDescription, long offset, long length) {
        if (offset < 0 || length < 0 || offset + length > length()) {
            throw new IllegalArgumentException(
                "slice() "
                    + sliceDescription
                    + " out of bounds: offset="
                    + offset
                    + ",length="
                    + length
                    + ",fileLength="
                    + length()
                    + ": "
                    + this
            );
        }
        final SharedCachedBlobContainerIndexInput slice = new SharedCachedBlobContainerIndexInput(
            getFullSliceDescription(sliceDescription),
            directory,
            fileInfo,
            context,
            stats,
            this.offset + offset,
            length,
            sharedCacheFile,
            defaultRangeSize,
            recoveryRangeSize
        );
        slice.isClone = true;
        return slice;
    }

    @Override
    public String toString() {
        return "CachedBlobContainerIndexInput{"
            + "sharedCacheFile="
            + sharedCacheFile
            + ", offset="
            + offset
            + ", length="
            + length()
            + ", position="
            + getFilePointer()
            + ", rangeSize="
            + getDefaultRangeSize()
            + ", directory="
            + directory
            + '}';
    }

    private static class CacheFileReference {

        private final long fileLength;
        private final CacheKey cacheKey;
        private final SearchableSnapshotDirectory directory;
        private final AtomicReference<CacheFile> cacheFile = new AtomicReference<>(); // caches the last used CacheFile for convenience

        private CacheFileReference(SearchableSnapshotDirectory directory, String fileName, long fileLength) {
            this.cacheKey = directory.createCacheKey(fileName);
            this.fileLength = fileLength;
            this.directory = directory;
        }

        CacheFile get() throws Exception {
            CacheFile currentCacheFile = cacheFile.get();
            if (currentCacheFile != null && currentCacheFile.isEvicted() == false) {
                // fast path
                return currentCacheFile;
            }

            // evicted or does not exist yet, create another instance
            if (currentCacheFile.isEvicted()) {

            }
            final CacheFile newCacheFile = directory.getCacheFile(cacheKey, fileLength);
            synchronized (this) {
                currentCacheFile = cacheFile.get();
                if (currentCacheFile != null) {
                    return currentCacheFile;
                }
                final CacheFile previousCacheFile = cacheFile.getAndSet(newCacheFile);
                assert previousCacheFile == null;
                return newCacheFile;
            }
        }

        @Override
        public String toString() {
            return "CacheFileReference{"
                + "cacheKey='"
                + cacheKey
                + '\''
                + ", fileLength="
                + fileLength
                + ", acquired="
                + (cacheFile.get() != null)
                + '}';
        }
    }

    private static boolean assertFileChannelOpen(FileChannel fileChannel) {
        assert fileChannel != null;
        assert fileChannel.isOpen();
        return true;
    }

    private static boolean isCacheFetchAsyncThread(final String threadName) {
        return threadName.contains('[' + SearchableSnapshotsConstants.CACHE_FETCH_ASYNC_THREAD_POOL_NAME + ']');
    }

    private static boolean assertCurrentThreadMayWriteCacheFile() {
        final String threadName = Thread.currentThread().getName();
        assert isCacheFetchAsyncThread(threadName) : "expected the current thread ["
            + threadName
            + "] to belong to the cache fetch async thread pool";
        return true;
    }

    private static boolean assertCurrentThreadIsNotCacheFetchAsync() {
        final String threadName = Thread.currentThread().getName();
        assert false == isCacheFetchAsyncThread(threadName) : "expected the current thread ["
            + threadName
            + "] to belong to the cache fetch async thread pool";
        return true;
    }
}
