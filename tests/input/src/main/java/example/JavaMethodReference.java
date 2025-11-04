package example;

import java.util.stream.Stream;

class JavaMethodReference {
  public void method() {
    Stream.of("hello", "world").map(String::length).forEach(System.out::println);
  }
}
