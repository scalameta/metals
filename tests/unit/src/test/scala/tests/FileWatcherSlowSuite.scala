package tests

import java.nio.file.Files
import scala.meta.internal.metals.RecursivelyDelete

object FileWatcherSlowSuite extends BaseSlowSuite("file-watcher") {
  testAsync("basic") {
    cleanCompileCache("a")
    cleanCompileCache("b")
    cleanCompileCache("c")
    RecursivelyDelete(workspace.resolve("a"))
    for {
      _ <- server.initialize(
        """
          |/metals.json
          |{
          |  "a": { },
          |  "b": { },
          |  "c": { "dependsOn": [ "a" ] }
          |}
          |/a/src/main/scala/A.scala
          |package a
          |object A
          |/b/src/main/scala/B.scala
          |package b
          |object B
          |/c/src/main/scala/C.scala
          |package c
          |object C {
          |  println(a.ScalaFileEvent)
          |  println(new a.JavaFileEvent)
          |}
        """.stripMargin
      )
      _ = FileWrites.write(
        workspace.resolve("a/src/main/scala/A.scala"),
        s"""
           |package a
           |object A 
           |object ScalaFileEvent
           |""".stripMargin
      )
      _ = {
        val JavaFileEvent =
          workspace.resolve("a/src/main/java/a/JavaFileEvent.java")
        Files.createDirectories(JavaFileEvent.toNIO.getParent)
        FileWrites.write(
          JavaFileEvent,
          s"""
             |package a;
             |public class JavaFileEvent {}
             |""".stripMargin
        )
      }
      _ = {
        val CFileEvent =
          workspace.resolve("a/src/main/c/a/main.c")
        Files.createDirectories(CFileEvent.toNIO.getParent)
        FileWrites.write(
          CFileEvent,
          s"""
             |#include <stdio.h>
             |void main(char **args) { printf("Hello World!\n"); }
             |""".stripMargin
        )
      }
      _ <- server.didOpen("b/src/main/scala/B.scala")
      _ <- server.didOpen("c/src/main/scala/C.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """
          |/b/src/main/scala/B.scala
          |package b
          |object B/*L1*/
          |/c/src/main/scala/C.scala
          |package c
          |object C/*L1*/ {
          |  println/*Predef.scala*/(a.ScalaFileEvent/*A.scala:3*/)
          |  println/*Predef.scala*/(new a.JavaFileEvent/*JavaFileEvent.java:2*/)
          |}
          |""".stripMargin
      )
      _ = assertIsNotDirectory(workspace.resolve("a/src/main/scala-2.12"))
      _ = assertIsNotDirectory(workspace.resolve("b/src/main/scala-2.12"))
      _ = assertIsNotDirectory(workspace.resolve("c/src/main/scala-2.12"))
      _ = assertIsNotDirectory(workspace.resolve("b/src/main/java"))
      _ = assertIsNotDirectory(workspace.resolve("c/src/main/java"))
    } yield ()
  }
}
