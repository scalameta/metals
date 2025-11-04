package example;

import java.io.IOException;

public class JavaExtends {

  enum Color implements AutoCloseable, Runnable {
    RED("red"),
    GREEN("green"),
    BLUE("blue");

    private final String name;

    Color(String name) {
      this.name = name;
    }

    @Override
    public void close() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void run() {
      System.out.println("Running...");
    }
  }

  interface MyInterface extends AutoCloseable {
    void myMethod();
  }

  record MyRecord(String name) implements MyInterface, Runnable {
    @Override
    public void close() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void myMethod() {
      System.out.println("MyMethod");
    }

    @Override
    public void run() {
      System.out.println("Running...");
    }
  }

  class MyClass extends Exception implements MyInterface, Runnable {

    @Override
    public void close() throws IOException {
      System.out.println("Closing...");
    }

    @Override
    public void myMethod() {
      System.out.println("MyMethod");
    }

    @Override
    public String getMessage() {
      return "MyClass.getMessage()";
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
      return this;
    }

    @Override
    public void run() {
      System.out.println("Running...");
    }
  }
}
