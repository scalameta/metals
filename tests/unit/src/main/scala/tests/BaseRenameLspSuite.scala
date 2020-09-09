package tests

import scala.concurrent.Future

import munit.Location
import munit.TestOptions

class BaseRenameLspSuite(name: String) extends BaseLspSuite(name) {

  protected def libraryDependencies: List[String] = Nil
  protected def compilerPlugins: List[String] = Nil

  def same(
      name: String,
      input: String
  )(implicit loc: Location): Unit =
    check(
      name,
      input,
      "SHOULD_NOT_BE_RENAMED",
      notRenamed = true
    )

  def renamed(
      name: TestOptions,
      input: String,
      newName: String,
      nonOpened: Set[String] = Set.empty,
      breakingChange: String => String = identity[String],
      fileRenames: Map[String, String] = Map.empty,
      scalaVersion: Option[String] = None
  )(implicit loc: Location): Unit =
    check(
      name,
      input,
      newName,
      notRenamed = false,
      nonOpened = nonOpened,
      breakingChange,
      fileRenames,
      scalaVersion
    )

  private def check(
      name: TestOptions,
      input: String,
      newName: String,
      notRenamed: Boolean,
      nonOpened: Set[String] = Set.empty,
      breakingChange: String => String = identity[String],
      fileRenames: Map[String, String] = Map.empty,
      scalaVersion: Option[String] = None
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()
      val allMarkersRegex = "(<<|>>|@@|##.*##)"
      val files = FileLayout.mapFromString(input)
      val expectedFiles = files.map {
        case (file, code) =>
          fileRenames.getOrElse(file, file) -> {
            val expected = if (!notRenamed) {
              code
                .replaceAll("\\<\\<\\S*\\>\\>", newName)
                .replaceAll("(##|@@)", "")
            } else {
              code.replaceAll(allMarkersRegex, "")
            }
            "\n" + breakingChange(expected)
          }
      }

      val (filename, edit) = files
        .find(_._2.contains("@@"))
        .getOrElse {
          throw new IllegalArgumentException(
            "No `@@` was defined that specifies cursor position"
          )
        }

      val openedFiles = files.keySet.diff(nonOpened)
      val fullInput = input.replaceAll(allMarkersRegex, "")
      val actualScalaVersion = scalaVersion.getOrElse(BuildInfo.scalaVersion)
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{
             |  "a" : {
             |    "scalaVersion": "$actualScalaVersion",
             |    "compilerPlugins": ${toJsonArray(compilerPlugins)},
             |    "libraryDependencies": ${toJsonArray(libraryDependencies)}
             |  },
             |  "b" : {
             |    "scalaVersion": "$actualScalaVersion",
             |    "dependsOn": [ "a" ]
             |  }
             |}
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
        // change the code to make sure edit distance is being used
        _ <- Future.sequence {
          openedFiles.map { file =>
            server.didChange(file) { code => "\n" + code }
          }
        }
        _ <- server.assertRename(
          filename,
          edit.replaceAll("(<<|>>|##.*##)", ""),
          expectedFiles,
          files.keySet,
          newName
        )
      } yield ()
    }
  }
}
