package /*example(Module):1*/example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

@Deprecated(since = "1.0", forRemoval = true)
class /*example.JavaAnnotation(Class):7*//*example.JavaAnnotation#JavaAnnotation(Constructor):7*/JavaAnnotation {

  @Target({ElementType.TYPE})
  @interface /*example.JavaAnnotation#MyAnnotation(Class):10*/MyAnnotation {
    String[] /*example.JavaAnnotation#MyAnnotation#values(Method):11*/values() default {"default"};
  }

  public static final String /*example.JavaAnnotation#CONSTANT(Field):14*/CONSTANT = "constant";

  @MyAnnotation(values = {CONSTANT, "value2"})
  class /*example.JavaAnnotation#MyClass(Class):17*//*example.JavaAnnotation#MyClass#MyClass(Constructor):17*/MyClass</*example.JavaAnnotation#MyClass#T(TypeParameter):17*/T> {
    void /*example.JavaAnnotation#MyClass#method(Method):18*/method(T t) {
      System.out.println(t);
    }
  }
}