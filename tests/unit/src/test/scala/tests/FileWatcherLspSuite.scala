package tests

import java.nio.file.Files

import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.RecursivelyDelete

import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.FileChangeType
import org.eclipse.lsp4j.FileEvent

class FileWatcherLspSuite extends BaseLspSuite("file-watcher") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(TestingServer.TestDefault)

  // Ignored because we disabled the Metals file watcher https://github.com/REDACTED_ORG/metals/pull/59
  // in favor of relying on LSP file watcher events, which are not sent in the test below.
  test("basic".ignore, withoutVirtualDocs = true, maxRetry = 3) {
    cleanCompileCache("a")
    cleanCompileCache("b")
    cleanCompileCache("c")
    RecursivelyDelete(workspace.resolve("a"))
    RecursivelyDelete(workspace.resolve("b"))
    RecursivelyDelete(workspace.resolve("c"))
    val JavaFileEvent =
      workspace.resolve("a/src/main/java/a/JavaFileEvent.java")
    val SingleFileEvent =
      workspace.resolve("a/weird/path/d/D.scala")
    Files.createDirectories(JavaFileEvent.toNIO.getParent)
    Files.createDirectories(workspace.resolve("a/src/main/scala").toNIO)
    Files.createDirectories(workspace.resolve("b/src/main/scala").toNIO)
    Files.createDirectories(workspace.resolve("c/src/main/scala").toNIO)
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": { "additionalSources" : ["a/weird/path/d/D.scala", "non/existent/one/e/E.scala"] },
          |  "b": { },
          |  "c": { "dependsOn": [ "a" ] }
          |}
          |/a/src/main/scala/A.scala
          |package a
          |object A
          |/a/weird/path/d/D.scala
          |package d
          |object D
          |/b/src/main/scala/B.scala
          |package b
          |object B
          |/c/src/main/scala/C.scala
          |package c
          |object C {
          |  println(a.ScalaFileEvent)
          |  println(new a.JavaFileEvent)
          |  println(d.SingleFileEvent)
          |}
        """.stripMargin
      )
      _ = FileWrites.write(
        workspace.resolve("a/src/main/scala/A.scala"),
        s"""
           |package a
           |object A
           |object ScalaFileEvent
           |""".stripMargin,
      )
      _ = {
        // Should generate an event
        FileWrites.write(
          JavaFileEvent,
          s"""
             |package a;
             |public class JavaFileEvent {}
             |""".stripMargin,
        )
      }
      _ = {
        // Should generate an event
        FileWrites.write(
          SingleFileEvent,
          s"""
             |package d
             |object D
             |object SingleFileEvent
             |""".stripMargin,
        )
      }
      _ = {
        // Should not generate a event
        val CFileEvent =
          workspace.resolve("a/src/main/c/a/main.c")
        Files.createDirectories(CFileEvent.toNIO.getParent)
        FileWrites.write(
          CFileEvent,
          s"""
             |#include <stdio.h>
             |void main(char **args) { printf("Hello World!\n"); }
             |""".stripMargin,
        )
      }
      _ <- server.didOpen("b/src/main/scala/B.scala")
      _ <- server.didOpen("c/src/main/scala/C.scala")
      _ = assertNoDiff(client.workspaceDiagnostics, "")
      _ = assertNoDiff(
        server.workspaceDefinitions,
        """|/b/src/main/scala/B.scala
           |package b
           |object B/*L1*/
           |/c/src/main/scala/C.scala
           |package c
           |object C/*L1*/ {
           |  println/*Predef.scala*/(a.ScalaFileEvent/*A.scala:3*/)
           |  println/*Predef.scala*/(new a.JavaFileEvent/*JavaFileEvent.java:2*/)
           |  println/*Predef.scala*/(d.SingleFileEvent/*D.scala:3*/)
           |}
           |""".stripMargin,
      )
      _ = assertIsNotDirectory(workspace.resolve("a/src/main/scala-2.12"))
      _ = assertIsNotDirectory(workspace.resolve("b/src/main/scala-2.12"))
      _ = assertIsNotDirectory(workspace.resolve("c/src/main/scala-2.12"))
      _ = assertIsNotDirectory(workspace.resolve("b/src/main/java"))
      _ = assertIsNotDirectory(workspace.resolve("c/src/main/java"))
      _ = assertIsNotDirectory(workspace.resolve("a/non/existent/one/e"))
    } yield ()
  }

  test("didChangeWatchedFiles-notification") {
    cleanWorkspace()
    FileLayout.fromString(
      s"""|/metals.json
          |{
          |  "a": { }
          |}
          |/a/src/main/scala/A.scala
          |package a
          |object A
          |/buildd.sbt
          |
          |""".stripMargin,
      workspace,
    )
    server.client.switchBuildTool = Messages.NewBuildToolDetected.switch

    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ = server.headServer
      _ = Files.delete(workspace.resolve("buildd.sbt").toNIO)
      _ = FileLayout.fromString(
        s"""|/build.sbt
            |""".stripMargin,
        workspace,
      )
      _ <- server.fullServer
        .didChangeWatchedFiles(
          new DidChangeWatchedFilesParams(
            List(
              new FileEvent(
                workspace.resolve("buildd.sbt").toURI.toString(),
                FileChangeType.Deleted,
              ),
              new FileEvent(
                workspace.resolve("build.sbt").toURI.toString(),
                FileChangeType.Created,
              ),
            ).asJava
          )
        )
        .asScala
      _ = assertContains(
        client.workspaceMessageRequests,
        Messages.ImportBuild.params("sbt").getMessage(),
      )
    } yield ()
  }
}
