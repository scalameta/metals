package scala.meta.internal.bsp

import scala.util.Try

import scala.meta.internal.builds.ScalaCliBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object ScalaCliBspScope {
  def inScope(root: AbsolutePath, file: AbsolutePath): Boolean = {
    val roots = scalaCliBspRoot(root)
    roots.isEmpty || roots.exists(bspRoot =>
      file.toNIO.startsWith(bspRoot.toNIO)
    )
  }

  def scalaCliBspRoot(root: AbsolutePath): List[AbsolutePath] =
    for {
      path <- ScalaCliBuildTool.pathsToScalaCliBsp(root)
      text <- path.readTextOpt.toList
      json = ujson.read(text)
      args <- json("argv").arrOpt.toList
      tailArgs = args.toList.flatMap(_.strOpt).dropWhile(_ != "bsp")
      rootArg <- tailArgs match {
        case "bsp" :: tail => dropOptions(tail).takeWhile(!_.startsWith("-"))
        case _ => Nil
      }
      rootPath <- Try(AbsolutePath(rootArg)(root).dealias).toOption
      if rootPath.exists
    } yield rootPath

  private def dropOptions(args: List[String]): List[String] =
    args match {
      case s"--$_" :: _ :: tail => dropOptions(tail)
      case rest => rest
    }
}
