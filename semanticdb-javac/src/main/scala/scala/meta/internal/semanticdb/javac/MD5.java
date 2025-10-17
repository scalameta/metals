package scala.meta.internal.semanticdb.javac;

import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Utility to compute MD5 checksums of strings. */
public final class MD5 {
	private static final char[] HEX_ARRAY;

	static {
		HEX_ARRAY = "0123456789ABCDEF".toCharArray();
	}

	private static String bytesToHex(byte[] bytes) {
		char[] hexChars = new char[bytes.length * 2];
		int j = 0;
		while (j < bytes.length) {
			int v = bytes[j] & 0xFF;
			hexChars[j * 2] = HEX_ARRAY[v >>> 4];
			hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
			j += 1;
		}
		return new String(hexChars);
	}

	public static String digest(CharSequence chars) throws NoSuchAlgorithmException {
		MessageDigest md5 = MessageDigest.getInstance("MD5");
		return bytesToHex(md5.digest(chars.toString().getBytes()));
	}
}
