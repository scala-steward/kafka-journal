package com.evolution.kafka.journal.execution

private[journal] object UncaughtExceptionHandler {

  val default: Thread.UncaughtExceptionHandler = new Thread.UncaughtExceptionHandler {
    def uncaughtException(thread: Thread, error: Throwable): Unit = {
      Thread.getDefaultUncaughtExceptionHandler match {
        case null => error.printStackTrace()
        case handler => handler.uncaughtException(Thread.currentThread(), error)
      }
    }
  }
}
