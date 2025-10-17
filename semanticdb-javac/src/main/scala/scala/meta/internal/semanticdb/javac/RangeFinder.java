package scala.meta.internal.semanticdb.javac;

import javax.tools.Diagnostic;

import javax.lang.model.element.Element;
import java.util.Optional;

public class RangeFinder {
	public static class StartEndRange {
		public int start;
		public int end;

		StartEndRange(int start, int end) {
			this.start = start;
			this.end = end;
		}
	}

	public static Optional<StartEndRange> findRange(Element element, String name, int originalStartPos,
			int originalEndPos, String source, boolean fromEnd) {
		int startPos = findNameIn(name, originalStartPos, originalEndPos, element, source, fromEnd);
		int endPos = startPos + name.length();

		if (endPos == -1 || startPos == -1) {
			return Optional.empty();
		}

		return Optional.of(new StartEndRange(startPos, endPos));
	}

	private static int findNameFromEnd(String name, int originalStartPos, int originalEndPos, int end, Element element,
			String source) {
		if (end < 0)
			return -1;
		int offset = source.lastIndexOf(name, end);
		if (offset == -1 && originalStartPos != Diagnostic.NOPOS && originalEndPos != Diagnostic.NOPOS)
			return originalStartPos;
		if (offset == -1) {
			return -1;
		}
		int endOfWord = offset + name.length();
		// found name in wrong word? e.g. finding `"A"` in `A("A")`
		if (offset > 0 && Character.isJavaIdentifierPart(source.charAt(offset - 1)))
			return findNameFromEnd(name, originalStartPos, originalEndPos, offset - 1, element, source);
		if (endOfWord < source.length() && Character.isJavaIdentifierPart(source.charAt(endOfWord)))
			return findNameFromEnd(name, originalStartPos, originalEndPos, offset - 1, element, source);

		return offset;
	}

	private static int findNameFromStart(String name, int start, int originalStartPos, int originalEndPos,
			Element element, String source) {
		if (start >= source.length())
			return -1;
		int offset = source.indexOf(name, start);
		if (offset == -1 && originalStartPos != Diagnostic.NOPOS && originalEndPos != Diagnostic.NOPOS)
			return originalStartPos;
		if (offset == -1) {
			return -1;
		}
		int end = offset + name.length();
		// found name in wrong word? e.g. finding `"A"` in `A("A")`
		if (offset > 0 && Character.isJavaIdentifierPart(source.charAt(offset - 1)))
			return findNameFromStart(name, end + 1, originalStartPos, originalEndPos, element, source);
		if (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end)))
			return findNameFromStart(name, end + 1, originalStartPos, originalEndPos, element, source);

		return offset;
	}

	private static int findNameIn(String name, int start, int end, Element element, String source, boolean fromEnd) {
		if (source.length() == 0)
			return -1;

		int offset;
		if (fromEnd)
			offset = findNameFromEnd(name, start, start, end, element, source);
		else
			offset = findNameFromStart(name, start, end, end, element, source);
		if (offset > -1) {
			return offset;
		}
		return -1;
	}
}
