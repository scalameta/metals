package example;

>>imports>>import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
>>comment>>/**
 * some
 * comment
 * inside
 * imports
 */<<comment<<
import java.nio.file.Files;
import java.nio.file.Path;<<imports<<

   >>comment>>/**
 Very
 long
 comment
 to
 wrap
 */<<comment<<
public class Main >>region>>{

    abstract class A >>region>>{
        abstract void hello();
        abstract void aaa();
    }<<region<<

    abstract class B extends A >>region>>{
        @Override
        void hello() {
            System.out.println("Hello!");}
    }<<region<<

    class C extends B >>region>>{
        @Override
        void hello() {
            System.out.println("Bye!");}

        String hello(String str) >>region>>{
            System.out.println("Bye!");
            return "asssd";
        }<<region<<

        void openingCommentInStr() {
            System.out.println("/* ignore");
        }

        void closingCommentInStr() {
            System.out.println("*/ ignore");
        }

        void handleLineWithBlockAndCode() >>region>>{
            if (true) >>region>>{
                // do something
                // to pad lines
            }<<region<< else >>region>>{
                // do something
                // to pad lines
            }<<region<<
        }<<region<<
    }<<region<<
}<<region<<
class D >>region>>{
    static >>region>>{
        >>comment>>/*
         check
         second
         class
         folds
         */<<comment<<
    }<<region<<
}<<region<<