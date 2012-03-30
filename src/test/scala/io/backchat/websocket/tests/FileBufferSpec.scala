package io.backchat.websocket
package tests

import org.specs2.time.NoTimeConversions
import org.specs2.Specification
import java.io.File
import org.apache.commons.io.{FilenameUtils, FileUtils}
import net.liftweb.json.DefaultFormats
import scala.io.Source
import collection.JavaConverters._
import org.specs2.specification.{Fragments, Step}
import java.util.concurrent.{Executors, ConcurrentLinkedQueue}
import akka.dispatch.{Await, Future, ExecutionContext}
import akka.util.duration._
import akka.actor.ActorSystem
import collection.mutable.{ArrayBuffer, Buffer, SynchronizedBuffer, ListBuffer}
import java.util.concurrent.atomic.AtomicInteger

class FileBufferSpec extends Specification with NoTimeConversions { def is =
  "A FileBuffer should" ^
    "create the path to the file if it doesn't exist" ! createsPath ^
    "write to a file while the buffer is open" ! writesToFile ^
    "write to memory buffer while draining" ! writesToMemory ^
    "drain the buffers" ! drainsBuffers ^
    "not fail under concurrent load" ! handlesConcurrentLoads ^
  end

  implicit val formats = DefaultFormats
  implicit val executionContext = ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  override def map(fs: => Fragments) = super.map(fs) ^ Step(executionContext.shutdown())

  def createsPath = {
    val logPath = new File("./test-work/testing/and/such/buffer.log")
    val workPath = new File("./test-work")
    if (workPath.exists()) FileUtils.deleteDirectory(workPath)
    val buff = new FileBuffer(logPath)
    buff.open()
    val res = logPath.getParentFile.exists must beTrue
    FileUtils.deleteDirectory(workPath)
    buff.close()
    res
  }

  def writesToFile = {
    FileUtils.deleteQuietly(new File("./test-work2"))
    val logPath = new File("./test-work2/buffer.log")
    val buff = new FileBuffer(logPath)
    val exp1 = TextMessage("the first message")
    val exp2 = TextMessage("the second message")
    buff.open()
    buff.write(exp1)
    buff.write(exp2)
    buff.close()
    val lines = Source.fromFile(logPath).getLines().toList map WebSocket.ParseToWebSocketOutMessage.apply
    FileUtils.deleteQuietly(new File("./test-work2"))
    lines must haveTheSameElementsAs(List(exp1, exp2))
  }

  def writesToMemory = {
    val logPath = new File("./test-work3/buffer.log")
    val exp1 = TextMessage("the first message")
    val exp2 = TextMessage("the second message")
    val queue = new ConcurrentLinkedQueue[String]()
    val buff = new FileBuffer(logPath, false, queue)
    buff.open()
    buff.write(exp1)
    buff.write(exp2)
    val lst = queue.asScala.toList
    buff.close()
    FileUtils.deleteDirectory(new File("./test-work3"))
    lst must haveTheSameElementsAs(List(exp1.content, exp2.content))
  }

  def drainsBuffers = {
    val logPath = new File("./test-work4/buffer.log")
    val buff = new FileBuffer(logPath)
    val exp1 = TextMessage("the first message")
    val exp2 = TextMessage("the second message")
    buff.open()
    buff.write(exp1)
    buff.write(exp2)
    val lines = new ListBuffer[WebSocketOutMessage]
    Await.ready(buff drain { out =>
      Future {
        lines += out
        Success
      }
    }, 5 seconds)
    buff.close()
    FileUtils.deleteQuietly(new File("./test-work4"))
    lines must haveTheSameElementsAs(List(exp1, exp2))
  }

  def handlesConcurrentLoads = {
    val system = ActorSystem("filebufferconc")
    val logPath = new File("./test-work5/buffer.log")
    val buff = new FileBuffer(logPath)
    val lines = new ArrayBuffer[WebSocketOutMessage] with SynchronizedBuffer[WebSocketOutMessage]
    buff.open()
    system.scheduler.schedule(200 millis, 200 millis) {
      Await.ready(buff drain { out =>
        Future {
          lines += out
          Success
        }
      }, 5 seconds)
    }
    (1 to 1000) foreach { s =>
        Thread.sleep(15)
        buff.write(TextMessage("message %s" format s))
    }
    //Await.ready(Future.sequence(futs), 5 seconds)

    Await.ready(buff drain { out =>
          Future {
            lines += out
            Success
          }
        }, 5 seconds)
    buff.close()
    FileUtils.deleteQuietly(new File("./test-work5"))
    system.shutdown()
    lines must haveSize(1000)
//    pending
  }

}
