package example;

import java.io.IOException;

public class JavaExtends/*example.JavaExtends#*//*example.JavaExtends#`<init>`().*/ {

  enum Color/*example.JavaExtends#Color#*/ implements AutoCloseable, Runnable {
    RED/*example.JavaExtends#Color#RED.*/("red"),
    GREEN/*example.JavaExtends#Color#GREEN.*/("green"),
    BLUE/*example.JavaExtends#Color#BLUE.*/("blue");

    private final String name/*example.JavaExtends#Color#name.*/;

    Color/*example.JavaExtends#Color#`<init>`().*/(String name) {
      this.name = name;
    }

    @Override
    public void close/*example.JavaExtends#Color#close().*/() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void run/*example.JavaExtends#Color#run().*/() {
      System.out.println("Running...");
    }
  }

  interface MyInterface/*example.JavaExtends#MyInterface#*/ extends AutoCloseable {
    void myMethod/*example.JavaExtends#MyInterface#myMethod().*/();
  }

  record MyRecord/*example.JavaExtends#MyRecord#*//*example.JavaExtends#MyRecord#`<init>`().*/(String name/*example.JavaExtends#MyRecord#name().*/) implements MyInterface, Runnable {
    @Override
    public void close/*example.JavaExtends#MyRecord#close().*/() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void myMethod/*example.JavaExtends#MyRecord#myMethod().*/() {
      System.out.println("MyMethod");
    }

    @Override
    public void run/*example.JavaExtends#MyRecord#run().*/() {
      System.out.println("Running...");
    }
  }

  class MyClass/*example.JavaExtends#MyClass#*//*example.JavaExtends#MyClass#`<init>`().*/ extends Exception implements MyInterface, Runnable {

    @Override
    public void close/*example.JavaExtends#MyClass#close().*/() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void myMethod/*example.JavaExtends#MyClass#myMethod().*/() {
      System.out.println("MyMethod");
    }

    @Override
    public String getMessage/*example.JavaExtends#MyClass#getMessage().*/() {
      return "MyClass.getMessage()";
    }

    @Override
    public synchronized Throwable fillInStackTrace/*example.JavaExtends#MyClass#fillInStackTrace().*/() {
      return this;
    }

    @Override
    public void run/*example.JavaExtends#MyClass#run().*/() {
      System.out.println("Running...");
    }
  }
}
