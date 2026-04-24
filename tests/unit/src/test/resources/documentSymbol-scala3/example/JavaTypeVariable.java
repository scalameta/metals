package /*example(Module):1*/example;

import java.lang.annotation.ElementType;
import java.lang.annotation.Target;

class /*example.JavaTypeVariable(Class):6*//*example.JavaTypeVariable#JavaTypeVariable(Constructor):6*/JavaTypeVariable</*example.JavaTypeVariable#T(TypeParameter):6*/T extends Comparable<T>> {
  <U> void /*example.JavaTypeVariable#method(Method):7*/method(U u, T t) {
    System.out.println(u);
    System.out.println(t);
  }

  class /*example.JavaTypeVariable#SubType(Class):12*//*example.JavaTypeVariable#SubType#SubType(Constructor):12*/SubType extends JavaTypeVariable<String> {
    <U> void /*example.JavaTypeVariable#SubType#method(Method):13*/method(U u, String t) {
      System.out.println(u);
      System.out.println(t);
    }
  }

  @Target({ElementType.TYPE_PARAMETER})
  @interface /*example.JavaTypeVariable#MyAnnotation(Class):20*/MyAnnotation {
    String /*example.JavaTypeVariable#MyAnnotation#value(Method):21*/value() default "default";
  }

  class /*example.JavaTypeVariable#MyClass(Class):24*//*example.JavaTypeVariable#MyClass#MyClass(Constructor):24*/MyClass<@MyAnnotation("value") /*example.JavaTypeVariable#MyClass#T(TypeParameter):24*/T extends Comparable<T>> {
    <U> void /*example.JavaTypeVariable#MyClass#method(Method):25*/method(U u, T t) {
      System.out.println(u);
      System.out.println(t);
    }
  }
}