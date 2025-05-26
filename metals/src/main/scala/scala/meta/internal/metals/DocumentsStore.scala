package scala.meta.internal.metals

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import com.google.gson.GsonBuilder

class DocumentsStore(folder: AbsolutePath) {
  private val path = folder.resolve(".metals/documents.json")

  private def load(): Set[String] = {
    // TODO: avoid reloading every time?
    val text = synchronized(Try(path.readText).getOrElse("{}"))
    val gson = new GsonBuilder().create()
    Try(gson.fromJson(text, classOf[java.util.Set[String]])) match {
      case Success(documents) =>
        documents.asScala.toSet
      case Failure(e) =>
        scribe.error(s"error parsing ${folder}: ${e.getMessage}")
        Set.empty
    }
  }

  private def store(documents: Set[String]) = {
    val gson = new GsonBuilder().setPrettyPrinting().create()
    val json = gson.toJson(documents.asJava)
    synchronized(path.writeText(json))
  }

  def has(path: AbsolutePath): Boolean = {
    val documents = load()
    val rel = path.toRelative(folder).toString
    documents.contains(rel)
  }

  def put(path: AbsolutePath): Boolean = {
    val documents = load()
    val rel = path.toRelative(folder).toString
    if (!documents.contains(rel)) {
      store(documents + rel)
      true
    } else {
      false
    }
  }

  def remove(path: AbsolutePath): Boolean = synchronized {
    val documents = load()
    val rel = path.toRelative(folder).toString
    if (documents.contains(rel)) {
      store(documents - rel)
      true
    } else {
      false
    }
  }

  def all(): Set[String] = {
    load()
  }
}
