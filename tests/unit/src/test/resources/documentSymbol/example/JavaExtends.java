package /*example(Module):1*/example;

import java.io.IOException;

public class /*example.JavaExtends(Class):5*//*example.JavaExtends#JavaExtends(Constructor):5*/JavaExtends {

  enum /*example.JavaExtends#Color(Class):7*/Color implements AutoCloseable, Runnable {
    /*example.JavaExtends#Color#RED(Field):8*/RED("red"),
    /*example.JavaExtends#Color#GREEN(Field):9*/GREEN("green"),
    /*example.JavaExtends#Color#BLUE(Field):10*/BLUE("blue");

    private final String /*example.JavaExtends#Color#name(Field):12*/name;

    /*example.JavaExtends#Color#Color(Constructor):14*/Color(String name) {
      this.name = name;
    }

    @Override
    public void /*example.JavaExtends#Color#close(Method):19*/close() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void /*example.JavaExtends#Color#run(Method):24*/run() {
      System.out.println("Running...");
    }
  }

  interface /*example.JavaExtends#MyInterface(Interface):29*/MyInterface extends AutoCloseable {
    void /*example.JavaExtends#MyInterface#myMethod(Method):30*/myMethod();
  }

  record /*example.JavaExtends#MyRecord(Class):33*//*example.JavaExtends#MyRecord#MyRecord(Constructor):33*/MyRecord(String /*example.JavaExtends#MyRecord#name(Method):33*/name) implements MyInterface, Runnable {
    @Override
    public void /*example.JavaExtends#MyRecord#close(Method):35*/close() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void /*example.JavaExtends#MyRecord#myMethod(Method):40*/myMethod() {
      System.out.println("MyMethod");
    }

    @Override
    public void /*example.JavaExtends#MyRecord#run(Method):45*/run() {
      System.out.println("Running...");
    }
  }

  class /*example.JavaExtends#MyClass(Class):50*//*example.JavaExtends#MyClass#MyClass(Constructor):50*/MyClass extends Exception implements MyInterface, Runnable {

    @Override
    public void /*example.JavaExtends#MyClass#close(Method):53*/close() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void /*example.JavaExtends#MyClass#myMethod(Method):58*/myMethod() {
      System.out.println("MyMethod");
    }

    @Override
    public String /*example.JavaExtends#MyClass#getMessage(Method):63*/getMessage() {
      return "MyClass.getMessage()";
    }

    @Override
    public synchronized Throwable /*example.JavaExtends#MyClass#fillInStackTrace(Method):68*/fillInStackTrace() {
      return this;
    }

    @Override
    public void /*example.JavaExtends#MyClass#run(Method):73*/run() {
      System.out.println("Running...");
    }
  }
}