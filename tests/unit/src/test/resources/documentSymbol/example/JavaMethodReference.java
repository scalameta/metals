package /*example(Module):1*/example;

import java.util.stream.Stream;

class /*example.JavaMethodReference(Class):5*//*example.JavaMethodReference#JavaMethodReference(Constructor):5*/JavaMethodReference {
  public void /*example.JavaMethodReference#method(Method):6*/method() {
    Stream.of("hello", "world").map(String::length).forEach(System.out::println);
  }
}