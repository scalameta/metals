package langserver.core

import com.typesafe.scalalogging.LazyLogging
import scala.util.Try

object Main extends LazyLogging {
  def main(args: Array[String]): Unit = {
    logger.info(s"Starting server in ${System.getenv("PWD")}")

    val server = Try {
      val s = new LanguageServer(System.in, System.out)
      s.start()
    }
    server.recover{case e => {logger.error(e.getMessage); e.printStackTrace} }
  }
}
