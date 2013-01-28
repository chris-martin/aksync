package org.codeswarm.aksync

import akka.testkit.{TestActorRef, TestKit}
import akka.actor.{Actor, Props, ActorSystem, ActorRef}, akka.event.Logging
import org.scalatest._
import scala.concurrent.duration.{Duration, DurationDouble, FiniteDuration}
import com.typesafe.config.ConfigFactory
import collection.mutable.ArrayBuffer

class ServerSuite extends FunSpec {

  class Fixture(
    val poolSizeRange: PoolSizeRange,
    val tokenRetryInterval: RetryInterval.Fixed = (0.25 seconds),
    val fast: Duration = (0.05 seconds)
  ) {

    implicit val system = ActorSystem("test", ConfigFactory.parseString("""
      akka {
        event-handlers = [org.codeswarm.aksync.NoLogging]
        loglevel = DEBUG
      }
    """))

    val testKit = new TestKit(system)

    implicit val self = testKit.testActor

    val lifecycle = new Lifecycle[Token]() {
      override def isAlive(a: Token): Boolean = a.alive
      override def actor: ActorRef = self
    }

    val serverProps = Props(new Server[Token](
      lifecycle = lifecycle,
      poolSizeRange = poolSizeRange,
      tokenRetryInterval = tokenRetryInterval
    ))

    lazy val server = TestActorRef(serverProps, "server")

    def expectNoMsg() {
      testKit.expectNoMsg()
    }

    def expectNoMsg(max: FiniteDuration) {
      testKit.expectNoMsg(max)
    }

    def expectMsg[T](obj: T): T = testKit.expectMsg(obj)

    def expectMsg[T](max: FiniteDuration, obj: T): T = testKit.expectMsg(max, obj)

    def expectLease[A](token: A): Lease[A] =
      testKit.expectMsgPF () {
        case l: Lease[_] if l.token == token =>
          l.asInstanceOf[Lease[A]]
      }

    def expectDestroy[A](token: A): Lifecycle.Destroy[A] =
      testKit.expectMsgPF () {
        case d: Lifecycle.Destroy[_] if d.token == token =>
          d.asInstanceOf[Lifecycle.Destroy[A]]
      }

    object Tokens {
      val map = collection.mutable.HashMap[Int, Token]()
      def apply(i: Int): Token =
        map.get(i) match {
          case Some(token) =>
            token
          case None =>
            val token = new Token(i)
            map += i -> token
            token
        }
    }

  }

  def withFixture(test: Fixture => Any)(implicit poolSizeRange: PoolSizeRange) {

    val fixture = new Fixture(poolSizeRange)
    import fixture.system

    val logBuffer = new ArrayBuffer[String]
    val logActor = system.actorOf(Props(new LogActor(logBuffer)))
    akka.event.Logging.AllLogLevels.foreach(l =>
      system.eventStream.subscribe(logActor, akka.event.Logging.classFor(l)))

    fixture.server

    try {

      test(fixture)

      system.eventStream.setLogLevel(Logging.WarningLevel)
      system.shutdown()
      system.awaitTermination()

    } finally {
      logBuffer.foreach(info(_))
    }

  }

  describe ("A Server with pool size 0-1") {

    implicit val poolSizeRange: PoolSizeRange = 0 to 1

    it ("should initially do nothing") {
      withFixture { f => import f._

        expectNoMsg()

      }
    }

    it ("should request a token when a client requests a lease") {
      withFixture { f => import f._

        server ! Lease.Request
        expectMsg(Lifecycle.TokenRequest)

      }
    }

    it ("should issue a lease when one is becomes available") {
      withFixture { f => import f._

        server ! Lease.Request
        expectMsg(Lifecycle.TokenRequest)

        server ! Lifecycle.NewToken(Tokens(1))
        expectLease(Tokens(1))

      }
    }

    it ("""should not request a second token, and should not respond to a lease request
        |  while the token is already leased""".stripMargin) {
      withFixture { f => import f._

        server ! Lease.Request
        expectMsg(Lifecycle.TokenRequest)

        server ! Lease.Request

        server ! Lifecycle.NewToken(Tokens(1))
        expectLease(Tokens(1))

        expectNoMsg()

      }
    }

    it ("should re-issue the same token to a second client after the first client releases") {
      withFixture { f => import f._

        server ! Lease.Request
        expectMsg(Lifecycle.TokenRequest)

        server ! Lease.Request

        server ! Lifecycle.NewToken(Tokens(1))
        val lease = expectLease(Tokens(1))

        lease.release()
        expectLease(Tokens(1))

      }
    }

    it ("should destroy and replace the token after it dies") {
      withFixture { f => import f._

        server ! Lease.Request
        expectMsg(Lifecycle.TokenRequest)

        server ! Lease.Request

        server ! Lifecycle.NewToken(Tokens(1))
        val lease = expectLease(Tokens(1))

        Tokens(1).alive = false

        lease.release()
        expectDestroy(Tokens(1))
        expectMsg(Lifecycle.TokenRequest)

        server ! Lifecycle.NewToken(Tokens(2))
        expectLease(Tokens(2))

      }
    }

  }

  describe ("A Server with pool size 1-2") {

    implicit val poolSizeRange: PoolSizeRange = 1 to 2

    it ("should immediately request a token") {
      withFixture { f => import f._

        expectMsg(1.second, Lifecycle.TokenRequest)

      }
    }

    it ("should not immediately retry when a token request fails") {
      withFixture { f => import f._

        expectMsg(Lifecycle.TokenRequest)

        server ! Lifecycle.TokenUnavailable
        expectNoMsg(tokenRetryInterval/2)

      }
    }

    it ("should delay and retry after a token request fails") {
      withFixture { f => import f._

        expectMsg(Lifecycle.TokenRequest)

        server ! Lifecycle.TokenUnavailable
        expectMsg(tokenRetryInterval*3/2, Lifecycle.TokenRequest)

      }
    }

  }

}

class Token(val id: Int, var alive: Boolean = true)

class LogActor(val buffer: ArrayBuffer[String]) extends Actor {

  def receive = {

    case e: akka.event.Logging.LogEvent =>
      buffer += List(e.logSource, e.message).mkString(" ")

  }

}

class NoLogging extends Actor {

  def receive = {

    case Logging.InitializeLogger(_) =>
      sender ! Logging.LoggerInitialized

  }

}
