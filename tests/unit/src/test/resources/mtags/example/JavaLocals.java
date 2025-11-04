package example;

import java.util.Scanner;
import java.util.stream.Stream;

public class JavaLocals/*example.JavaLocals#*/ {
  record Point/*example.JavaLocals#Point#*//*example.JavaLocals#Point#`<init>`().*/(int x/*example.JavaLocals#Point#x().*//*example.JavaLocals#Point#x.*/, int y/*example.JavaLocals#Point#y().*//*example.JavaLocals#Point#y.*/) {}

  public int test/*example.JavaLocals#test().*/(int x) {
    int a = 1;
    int b = x;
    if (b > 0) {
      var c = a + b;
      return c;
    }
    Object p = new Point(1, 2);
    // TODO: instanceof binding not handled yet
    if (p instanceof Point p2) {
      a += p2.x();
      a += p2.y();
    }
    try {
      for (int i = 0; i < 10; i++) {
        a += i;
      }
    } catch (Exception e) {
      a += e.getMessage().length();
    }
    for (int e : new int[] {1, 2, 3}) {
      a += e;
    }
    try (var s = new Scanner(System.in)) {
      a += s.nextInt();
    }
    return Stream.of(a, b).map(i -> i * 2).reduce(0, (i, j) -> i + j);
  }
}
