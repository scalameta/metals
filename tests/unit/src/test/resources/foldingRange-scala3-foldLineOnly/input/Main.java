package example;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
/**
 * some
 * comment
 * inside
 * imports
 */
import java.nio.file.Files;
import java.nio.file.Path;

   /**
 Very
 long
 comment
 to
 wrap
 */
public class Main {

    abstract class A {
        abstract void hello();
        abstract void hello2();
    }

    abstract class B extends A {
        @Override
        void hello() {
            System.out.println("Hello!");}
    }

    class C extends B {
        @Override
        void hello() {
            System.out.println("Bye!");}

        String hello(String str) {
            System.out.println("Bye!");
            return "asssd";
        }

        void openingCommentInStr() {
            System.out.println("/* ignore");
        }

        void closingCommentInStr() {
            System.out.println("*/ ignore");
        }

        void handleLineWithBlockAndCode() {
            if (true) {
                // do something
                // to pad lines
            } else {
                // do something
                // to pad lines
            }
        }
    }
}
class D {
    static {
        /*
         check
         second
         class
         folds
         */
    }
}