package org.cryptomator.webdav.jackrabbit;

import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.jackrabbit.webdav.DavResourceFactory;
import org.apache.jackrabbit.webdav.DavResourceLocator;
import org.apache.jackrabbit.webdav.DavServletRequest;
import org.apache.jackrabbit.webdav.DavSession;
import org.apache.jackrabbit.webdav.io.OutputContext;
import org.apache.jackrabbit.webdav.lock.LockManager;
import org.cryptomator.crypto.Cryptor;
import org.cryptomator.crypto.exceptions.DecryptFailedException;
import org.eclipse.jetty.http.HttpHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * Delivers only the requested range of bytes from a file.
 * 
 * @see {@link https://tools.ietf.org/html/rfc7233#section-4}
 */
class EncryptedFilePart extends EncryptedFile {

	private static final Logger LOG = LoggerFactory.getLogger(EncryptedFilePart.class);
	private static final String BYTE_UNIT_PREFIX = "bytes=";
	private static final char RANGE_SET_SEP = ',';
	private static final char RANGE_SEP = '-';
	private static final Cache<DavResourceLocator, MacAuthenticationJob> cachedMacAuthenticationJobs = CacheBuilder.newBuilder().expireAfterWrite(10, TimeUnit.MINUTES).build();

	/**
	 * e.g. range -500 (gets the last 500 bytes) -> (-1, 500)
	 */
	private static final Long SUFFIX_BYTE_RANGE_LOWER = -1L;

	/**
	 * e.g. range 500- (gets all bytes from 500) -> (500, MAX_LONG)
	 */
	private static final Long SUFFIX_BYTE_RANGE_UPPER = Long.MAX_VALUE;

	private final Set<Pair<Long, Long>> requestedContentRanges = new HashSet<Pair<Long, Long>>();

	public EncryptedFilePart(DavResourceFactory factory, DavResourceLocator locator, DavSession session, DavServletRequest request, LockManager lockManager, Cryptor cryptor, CryptoWarningHandler cryptoWarningHandler,
			ExecutorService backgroundTaskExecutor) {
		super(factory, locator, session, lockManager, cryptor, cryptoWarningHandler);
		final String rangeHeader = request.getHeader(HttpHeader.RANGE.asString());
		if (rangeHeader == null) {
			throw new IllegalArgumentException("HTTP request doesn't contain a range header");
		}
		determineByteRanges(rangeHeader);

		synchronized (cachedMacAuthenticationJobs) {
			if (cachedMacAuthenticationJobs.getIfPresent(locator) == null) {
				final MacAuthenticationJob macAuthJob = new MacAuthenticationJob(locator);
				cachedMacAuthenticationJobs.put(locator, macAuthJob);
				backgroundTaskExecutor.submit(macAuthJob);
			}
		}

	}

	private void determineByteRanges(String rangeHeader) {
		final String byteRangeSet = StringUtils.removeStartIgnoreCase(rangeHeader, BYTE_UNIT_PREFIX);
		final String[] byteRanges = StringUtils.split(byteRangeSet, RANGE_SET_SEP);
		if (byteRanges.length == 0) {
			throw new IllegalArgumentException("Invalid range: " + rangeHeader);
		}
		for (final String byteRange : byteRanges) {
			final String[] bytePos = StringUtils.splitPreserveAllTokens(byteRange, RANGE_SEP);
			if (bytePos.length != 2 || bytePos[0].isEmpty() && bytePos[1].isEmpty()) {
				throw new IllegalArgumentException("Invalid range: " + rangeHeader);
			}
			final Long lower = bytePos[0].isEmpty() ? SUFFIX_BYTE_RANGE_LOWER : Long.valueOf(bytePos[0]);
			final Long upper = bytePos[1].isEmpty() ? SUFFIX_BYTE_RANGE_UPPER : Long.valueOf(bytePos[1]);
			if (lower > upper) {
				throw new IllegalArgumentException("Invalid range: " + rangeHeader);
			}
			requestedContentRanges.add(new ImmutablePair<Long, Long>(lower, upper));
		}
	}

	/**
	 * @return One range, that spans all requested ranges.
	 */
	private Pair<Long, Long> getUnionRange(Long fileSize) {
		final long lastByte = fileSize - 1;
		final MutablePair<Long, Long> result = new MutablePair<Long, Long>();
		for (Pair<Long, Long> range : requestedContentRanges) {
			final long left;
			final long right;
			if (SUFFIX_BYTE_RANGE_LOWER.equals(range.getLeft())) {
				left = lastByte - range.getRight();
				right = lastByte;
			} else if (SUFFIX_BYTE_RANGE_UPPER.equals(range.getRight())) {
				left = range.getLeft();
				right = lastByte;
			} else {
				left = range.getLeft();
				right = range.getRight();
			}
			if (result.getLeft() == null || left < result.getLeft()) {
				result.setLeft(left);
			}
			if (result.getRight() == null || right > result.getRight()) {
				result.setRight(right);
			}
		}
		return result;
	}

	@Override
	public void spool(OutputContext outputContext) throws IOException {
		final Path path = ResourcePathUtils.getPhysicalPath(this);
		if (Files.isRegularFile(path)) {
			outputContext.setModificationTime(Files.getLastModifiedTime(path).toMillis());
			try (final SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
				final Long fileSize = cryptor.decryptedContentLength(channel);
				final Pair<Long, Long> range = getUnionRange(fileSize);
				final Long rangeLength = range.getRight() - range.getLeft() + 1;
				outputContext.setContentLength(rangeLength);
				outputContext.setProperty(HttpHeader.CONTENT_RANGE.asString(), getContentRangeHeader(range.getLeft(), range.getRight(), fileSize));
				if (outputContext.hasStream()) {
					cryptor.decryptRange(channel, outputContext.getOutputStream(), range.getLeft(), rangeLength);
				}
			} catch (EOFException e) {
				if (LOG.isDebugEnabled()) {
					LOG.debug("Unexpected end of stream during delivery of partial content (client hung up).");
				}
			} catch (DecryptFailedException e) {
				throw new IOException("Error decrypting file " + path.toString(), e);
			}
		}
	}

	private String getContentRangeHeader(long firstByte, long lastByte, long completeLength) {
		return String.format("%d-%d/%d", firstByte, lastByte, completeLength);
	}

	private class MacAuthenticationJob implements Runnable {

		private final DavResourceLocator locator;

		public MacAuthenticationJob(final DavResourceLocator locator) {
			if (locator == null) {
				throw new IllegalArgumentException("locator must not be null.");
			}
			this.locator = locator;
		}

		@Override
		public void run() {
			final Path path = ResourcePathUtils.getPhysicalPath(locator);
			if (Files.isRegularFile(path) && Files.isReadable(path)) {
				try (final SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
					final boolean authentic = cryptor.isAuthentic(channel);
					if (!authentic) {
						cryptoWarningHandler.macAuthFailed(locator.getResourcePath());
					}
				} catch (ClosedByInterruptException ex) {
					LOG.debug("Couldn't finish MAC verification due to interruption of worker thread.");
				} catch (IOException e) {
					LOG.error("IOException during MAC verification of " + path.toString(), e);
				}
			}
		}

		@Override
		public int hashCode() {
			return locator.hashCode();
		}

		@Override
		public boolean equals(Object obj) {
			if (obj instanceof MacAuthenticationJob) {
				final MacAuthenticationJob other = (MacAuthenticationJob) obj;
				return this.locator.equals(other.locator);
			} else {
				return false;
			}
		}
	}

}
