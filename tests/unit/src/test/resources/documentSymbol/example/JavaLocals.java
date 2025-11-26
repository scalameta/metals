package /*example(Module):1*/example;

import java.util.Scanner;
import java.util.stream.Stream;

public class /*example.JavaLocals(Class):6*//*example.JavaLocals#JavaLocals(Constructor):6*/JavaLocals {
  record /*example.JavaLocals#Point(Class):7*//*example.JavaLocals#Point#Point(Constructor):7*/Point(int /*example.JavaLocals#Point#x(Method):7*/x, int /*example.JavaLocals#Point#y(Method):7*/y) {}

  public int /*example.JavaLocals#test(Method):9*/test(int x) {
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