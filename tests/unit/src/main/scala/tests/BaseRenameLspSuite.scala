package tests

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments.XtensionString
import scala.meta.internal.pc.Identifier

import munit.Location
import munit.TestOptions

abstract class BaseRenameLspSuite(name: String) extends BaseLspSuite(name) {

  protected def libraryDependencies: List[String] = Nil
  protected def compilerPlugins: List[String] = Nil

  def same(
      name: String,
      input: String,
  )(implicit loc: Location): Unit =
    check(
      name,
      input,
      "SHOULD_NOT_BE_RENAMED",
      notRenamed = true,
    )

  def renamed(
      name: TestOptions,
      input: String,
      newName: String,
      nonOpened: Set[String] = Set.empty,
      breakingChange: String => String = identity[String],
      fileRenames: Map[String, String] = Map.empty,
      scalaVersion: Option[String] = None,
      expectedError: Boolean = false,
      metalsJson: Option[String] = None,
  )(implicit loc: Location): Unit = {
    check(
      name,
      input,
      newName,
      notRenamed = false,
      nonOpened = nonOpened,
      breakingChange,
      fileRenames,
      scalaVersion,
      expectedError,
      metalsJson = metalsJson,
    )
  }

  private def check(
      name: TestOptions,
      input: String,
      newName: String,
      notRenamed: Boolean,
      nonOpened: Set[String] = Set.empty,
      breakingChange: String => String = identity[String],
      fileRenames: Map[String, String] = Map.empty,
      scalaVersion: Option[String] = None,
      expectedError: Boolean = false,
      metalsJson: Option[String] = None,
  )(implicit loc: Location): Unit = {
    test(name, maxRetry = { if (isCI) 3 else 0 }) {
      cleanWorkspace()
      val allMarkersRegex = "(<<|>>|@@|##.*##)"
      val files = FileLayout.mapFromString(input)

      val expectedFiles = (renamedTo: String) =>
        files.map { case (file, code) =>
          fileRenames.getOrElse(file, file) -> {
            val expectedName = Identifier.backtickWrap(renamedTo)
            val expected = if (!notRenamed) {
              code
                .replaceAll("\\<\\<\\S*\\>\\>", expectedName)
                .replaceAll("(##|@@)", "")
            } else {
              code.replaceAll(allMarkersRegex, "")
            }
            "\n" + breakingChange(expected)
          }
        }

      val singleRename = files
        .find(_._2.contains("@@"))

      val allRenameLocations = singleRename match {
        case None =>
          files.flatMap { case (file, code) =>
            code.indicesOf("<<").map { ind =>
              val updated =
                code.substring(0, ind + 3) + "@@" + code.substring(ind + 3)
              (file, updated, newName + ind)
            }

          }
        case Some((filename, edit)) => List((filename, edit, newName))
      }

      val openedFiles = files.keySet.diff(nonOpened)
      val fullInput = input.replaceAll(allMarkersRegex, "")
      for {
        _ <- initialize(
          s"""/metals.json
             |${metalsJson.getOrElse(defaultMetalsJson(scalaVersion))}
             |$fullInput""".stripMargin
        )
        _ <- Future.sequence {
          openedFiles.map { file => server.didOpen(file) }
        }
        // possible breaking changes for testing
        _ <- Future.sequence {
          openedFiles.map { file =>
            server.didSave(file) { code => breakingChange(code) }
          }
        }
        _ = if (!expectedError) assertNoDiagnostics()
        // change the code to make sure edit distance is being used
        _ <- Future.sequence {
          openedFiles.map { file =>
            server.didChange(file) { code => "\n" + code }
          }
        }
        allRenamed = allRenameLocations.map { case (filename, edit, renameTo) =>
          () =>
            server
              .assertRename(
                filename,
                edit.replaceAll("(<<|>>|##.*##)", ""),
                expectedFiles(renameTo),
                files.keySet,
                renameTo,
              )
              .flatMap {
                // Revert all files to initial state
                _ =>
                  Future
                    .sequence {
                      files.map { case (file, code) =>
                        server.didSave(file)(_ =>
                          code.replaceAll(allMarkersRegex, "")
                        )
                      }.toList

                    }
                    .map(_ => ())

              }
        }
        _ <- allRenamed.foldLeft(Future.unit) { case (acc, next) =>
          acc.flatMap(_ => next())
        }
      } yield ()
    }
  }

  private def defaultMetalsJson(scalaVersion: Option[String]): String = {
    val actualScalaVersion = scalaVersion.getOrElse(BuildInfo.scalaVersion)
    s"""|{
        |  "a" : {
        |    "scalaVersion": "$actualScalaVersion",
        |    "compilerPlugins": ${toJsonArray(compilerPlugins)},
        |    "libraryDependencies": ${toJsonArray(libraryDependencies)},
        |    "scalacOptions" : ["-Ymacro-annotations"]
        |  },
        |  "b" : {
        |    "scalaVersion": "$actualScalaVersion",
        |    "dependsOn": [ "a" ]
        |  }
        |}""".stripMargin
  }
}
