package scala.meta.internal.metals;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

public class LargeLimitedInputStream extends FilterInputStream {
	private static final int EOF = -1;

	private long bytesRemaining;
	private final boolean closeWrapped;
	private boolean isClosed;

	/**
	 * @param closeWrapped controls if the underlying {@link InputStream} should also be closed via {@link #close()}
	 */
	public LargeLimitedInputStream(final InputStream wrapped, final long maxBytesToRead, final boolean closeWrapped) {
		super(wrapped);
		if (maxBytesToRead < 0)
			throw new IllegalArgumentException("[maxBytesToRead] must be >= 0");
		bytesRemaining = maxBytesToRead;
		this.closeWrapped = closeWrapped;
	}

    @Override
    public int available() throws IOException {
        throw new IOException("available not supported");
    }

	@Override
	public void close() throws IOException {
		if (closeWrapped) {
			in.close();
		}
		isClosed = true;
	}

	@Override
	public int read() throws IOException {
		if (isClosed || bytesRemaining < 1)
			return EOF;

		final int data = in.read();
		if (data != EOF) {
			bytesRemaining--;
		}
		return data;
	}

	@Override
	public int read(final byte[] b, final int off, final int len) throws IOException {
		if (isClosed || bytesRemaining < 1)
			return EOF;

		final int bytesRead = in.read(b, off, Math.min(len, (int) Math.min(bytesRemaining, Integer.MAX_VALUE)));
		if (bytesRead != EOF) {
			bytesRemaining -= bytesRead;
		}
		return bytesRead;
	}

    @Override
    public synchronized void mark(final int readlimit) {}

    @Override
    public synchronized void reset() throws IOException {
        throw new IOException("mark/reset not supported");
    }

	@Override
	public long skip(final long n) throws IOException {
		final long skipped = in.skip(Math.min(n, bytesRemaining));
		bytesRemaining -= skipped;
		return skipped;
	}
}
