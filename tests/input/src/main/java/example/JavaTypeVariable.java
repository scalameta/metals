package example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

class JavaTypeVariable<T extends Comparable<T>> {
  <U> void method(U u, T t) {
    System.out.println(u);
    System.out.println(t);
  }

  class SubType extends JavaTypeVariable<String> {
    <U> void method(U u, String t) {
      System.out.println(u);
      System.out.println(t);
    }
  }

  @Target({ElementType.TYPE_PARAMETER})
  @interface MyAnnotation {
    String value() default "default";
  }

  class MyClass<@MyAnnotation("value") T extends Comparable<T>> {
    <U> void method(U u, T t) {
      System.out.println(u);
      System.out.println(t);
    }
  }
}
