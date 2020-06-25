package scala.meta.internal.process

import scala.util.control.NoStackTrace

class MessageOnlyException(message: String)
    extends RuntimeException(message)
    with NoStackTrace
