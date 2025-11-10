package example;

import java.util.stream.Stream;

class JavaMethodReference/*example.JavaMethodReference#*//*example.JavaMethodReference#`<init>`().*/ {
  public void method/*example.JavaMethodReference#method().*/() {
    Stream.of("hello", "world").map(String::length).forEach(System.out::println);
  }
}
