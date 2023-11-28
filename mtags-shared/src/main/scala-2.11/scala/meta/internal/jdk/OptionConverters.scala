package scala.meta.internal.jdk

import scala.collection.convert.DecorateAsJava
import scala.collection.convert.DecorateAsScala
import java.util.Optional

object OptionConverters {
  implicit class RichOptional[T](private val v: Optional[T]) extends AnyVal {
    def toScala: Option[A] = if (v.isPresent) Some(v.get) else None
  }
  implicit class RichOption[T](private val v: Option[T]) extends AnyVal {
    def toJava: Option[A] =
      if (v.isDefined) Optional.of(v.get) else Optional.empty()
  }
}
