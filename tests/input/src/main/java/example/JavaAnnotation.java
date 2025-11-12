package example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Deprecated(since = "1.0", forRemoval = true)
class JavaAnnotation {

  @Target({ElementType.TYPE})
  @interface MyAnnotation {
    String[] values() default {"default"};
  }

  public static final String CONSTANT = "constant";

  @MyAnnotation(values = {CONSTANT, "value2"})
  class MyClass<T> {
    void method(T t) {
      System.out.println(t);
    }
  }
}
