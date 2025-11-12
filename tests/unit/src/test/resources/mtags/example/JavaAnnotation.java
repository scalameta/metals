package example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Deprecated(since = "1.0", forRemoval = true)
class JavaAnnotation/*example.JavaAnnotation#*//*example.JavaAnnotation#`<init>`().*/ {

  @Target({ElementType.TYPE})
  @interface MyAnnotation/*example.JavaAnnotation#MyAnnotation#*/ {
    String[] values/*example.JavaAnnotation#MyAnnotation#values().*/() default {"default"};
  }

  public static final String CONSTANT/*example.JavaAnnotation#CONSTANT.*/ = "constant";

  @MyAnnotation(values = {CONSTANT, "value2"})
  class MyClass/*example.JavaAnnotation#MyClass#*//*example.JavaAnnotation#MyClass#`<init>`().*/<T/*example.JavaAnnotation#MyClass#[T]*/> {
    void method/*example.JavaAnnotation#MyClass#method().*/(T t) {
      System.out.println(t);
    }
  }
}
