package io.backchat.websocket

import java.io._
import java.util.concurrent.ConcurrentLinkedQueue


object FileBuffer {
  private object State extends Enumeration {
    val Closed, Draining, Open = Value
  }
}

trait BackupBuffer {
  def open()
  def close()
  def write(line: String)
  def drain(readLine: String => Unit)
}

class FileBuffer(file: File) extends Closeable {

  import FileBuffer._
  @volatile private[this] var output: PrintWriter = _
  private[this] val memoryBuffer = new ConcurrentLinkedQueue[String]()
  @volatile private[this] var state = State.Closed

  def open() = if (state == State.Closed) openFile(true)

  @inline private[this] def openFile(append: Boolean) {
    output = new PrintWriter(new BufferedOutputStream(new FileOutputStream(file, append)))
    state = State.Open
  }

  def write(line: String) = state match {
    case State.Open => output.println(line)
    case State.Closed | State.Draining => while(!memoryBuffer.offer(line)) {} // just loop until it's in please
  }

  def drain(readLine: (String => Unit)) = synchronized {
    state = State.Draining
    close()
    var input: BufferedReader = null
    try {
      input = new BufferedReader(new FileReader(file))
      var line = input.readLine()
      while(line.blankOption.isDefined) {
        readLine(line)
        line = input.readLine()
      }
      while(!memoryBuffer.isEmpty) {
        val line = memoryBuffer.poll()
        if (line.nonBlank) readLine(line)
      }
    } catch {
      case e =>
        System.err.println()
    } finally {
      if (input != null) {
        input.close()
      }
      openFile(false)
    }
  }

  def close() {
    if (state != State.Closed) {
      output.close()
      output = null
      state = State.Closed
    }
  }
}
