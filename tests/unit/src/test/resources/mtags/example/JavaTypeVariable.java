package example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

class JavaTypeVariable/*example.JavaTypeVariable#*//*example.JavaTypeVariable#`<init>`().*/<T/*example.JavaTypeVariable#[T]*/ extends Comparable<T>> {
  <U> void method/*example.JavaTypeVariable#method().*/(U u, T t) {
    System.out.println(u);
    System.out.println(t);
  }

  class SubType/*example.JavaTypeVariable#SubType#*//*example.JavaTypeVariable#SubType#`<init>`().*/ extends JavaTypeVariable<String> {
    <U> void method/*example.JavaTypeVariable#SubType#method().*/(U u, String t) {
      System.out.println(u);
      System.out.println(t);
    }
  }

  @Target({ElementType.TYPE_PARAMETER})
  @interface MyAnnotation/*example.JavaTypeVariable#MyAnnotation#*/ {
    String value/*example.JavaTypeVariable#MyAnnotation#value().*/() default "default";
  }

  class MyClass/*example.JavaTypeVariable#MyClass#*//*example.JavaTypeVariable#MyClass#`<init>`().*/<@MyAnnotation("value") T/*example.JavaTypeVariable#MyClass#[T]*/ extends Comparable<T>> {
    <U> void method/*example.JavaTypeVariable#MyClass#method().*/(U u, T t) {
      System.out.println(u);
      System.out.println(t);
    }
  }
}
