package example;

public record JavaRecord(int a, int b) {
	public JavaRecord(int a) {
		this(a, 0);
	}

	public JavaRecord(int a, int b, int c) {
		this(a, b + c);
	}

	public static JavaRecord of(int a) {
		return new JavaRecord(a);
	}

	public int sum() {
		return a + b;
	}
}