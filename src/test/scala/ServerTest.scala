package org.codeswarm.aksync

import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.actor.{Actor, Props, ActorSystem, ActorRef}, akka.event.Logging
import org.scalatest._
import scala.concurrent.duration.DurationDouble
import com.typesafe.config.ConfigFactory

class ServerTest(_system : ActorSystem) extends TestKit(_system) with ImplicitSender
    with FunSpec with OneInstancePerTest with BeforeAndAfter {

  import ServerTest._

  def this() = this(ActorSystem(
    "ServerTest",
    ConfigFactory.load(ConfigFactory.parseString("""
      akka {
        event-handlers = [org.codeswarm.aksync.NoLogging]
        loglevel = DEBUG
      }
    """))
  ))

  val logBuffer = collection.mutable.ArrayBuffer[String]()

  before {
    val logActor = system.actorOf(Props(new LogBufferActor(logBuffer)))
    akka.event.Logging.AllLogLevels.foreach(l =>
      system.eventStream.subscribe(logActor, akka.event.Logging.classFor(l)))
  }

  after {
    system.shutdown()
    system.awaitTermination()
    logBuffer.foreach(info(_))
  }

  val tokenRetryInterval = (0.25 seconds)
  val fast = (0.05 seconds)

  def createLifecycle = new Lifecycle[Token]() {
    override def isAlive(a: Token): Boolean = a.alive
    override def actor: ActorRef = testActor
  }

  def createServer(implicit poolSizeRange: PoolSizeRange) =
    TestActorRef(
      Props(new Server[Token](
        lifecycle = createLifecycle,
        poolSizeRange = poolSizeRange,
        tokenRetryInterval = tokenRetryInterval
      )),
      "server"
    )

  describe("A Server with pool size 0-1") {

    implicit val poolSizeRange = 0 to 1: PoolSizeRange

    it ("should initially do nothing") {
      val server = createServer
      expectNoMsg (0.5 seconds)
    }

    it ("should request a token when a client requests a lease") {
      val server = createServer
      server ! Lease.Request
      expectMsg (fast, Lifecycle.TokenRequest)
    }

  }
/*
    it ("should immediately request a token")

    test("test") {

      val server

      // Since the min pool size is 2, server requests a token immediately.
      expectMsg(Lifecycle.TokenRequest)

      // No token is available right now.
      server ! Lifecycle.TokenUnavailable

      // Request a lease. It will not be issued yet.
      server ! Lease.Request

      // The server should not immediately retry.
      expectNoMsg(tokenRetryInterval/2)

      // The server should retry after a brief delay.
      expectMsg(tokenRetryInterval*3/2, Lifecycle.TokenRequest)

      // Still no token available.
      server ! Lifecycle.TokenUnavailable

      // Again, the server should retry after a brief delay.
      expectMsg(tokenRetryInterval*3/2, Lifecycle.TokenRequest)

      // Actually reply with a token this time.
      val token1 = new Token(1)
      server ! Lifecycle.NewToken(token1)

      // Server immediately gives us a lease on the new token
      val lease = expectMsgPF(fast) { case x: Lease[_] if x.token == token1 => x }

      // Still not at pool size, so server requests again.
      expectMsg(Lifecycle.TokenRequest)

    }

  }
*/

}

object ServerTest {

  class Token(val id: Int, var alive: Boolean = true)

}

class LogBufferActor(buffer: collection.mutable.ArrayBuffer[String]) extends Actor {

  import akka.event.Logging._

  def receive = {
    case e: Error   => buffer += e.toString
    case e: Warning => buffer += e.toString
    case e: Info    => buffer += e.toString
    case e: Debug   => buffer += e.toString
  }

}

class NoLogging extends Actor {
  def receive = {
    case Logging.InitializeLogger(_) => sender ! Logging.LoggerInitialized
  }
}
