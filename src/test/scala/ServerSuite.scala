package org.codeswarm.aksync

import akka.testkit.{TestActorRef, TestKit}
import akka.actor.{Actor, Props, ActorSystem, ActorRef, ActorContext}, akka.event.Logging
import org.scalatest._
import scala.concurrent.duration.{Duration, DurationDouble, FiniteDuration}
import com.typesafe.config.ConfigFactory
import collection.mutable.ArrayBuffer

class ServerSuite extends FunSpec {

  class Fixture(
    val poolSizeRange: PoolSizeRange,
    val leaseTimeout: LeaseTimeout,
    val tokenRetryInterval: TokenRetryInterval.Fixed = (0.25 seconds),
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
      override def actor(implicit context: ActorContext): ActorRef = self
    }

    val serverProps = Props(new Server[Token](
      lifecycle = lifecycle,
      leaseTimeout = leaseTimeout,
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

    def expectMsg[T](obj: T, max: FiniteDuration): T = testKit.expectMsg(max, obj)

    def expectLease[A](token: A, max: Duration = Duration.Undefined): Lease[A] =
      testKit.expectMsgPF (max = max) {
        case x: Lease[_] if x.token == token =>
          x.asInstanceOf[Lease[A]]
      }

    def expectDead[A](token: A, max: Duration = Duration.Undefined): Lifecycle.Dead[A] =
      testKit.expectMsgPF (max = max) {
        case x: Lifecycle.Dead[_] if x.token == token =>
          x.asInstanceOf[Lifecycle.Dead[A]]
      }

    def expectRevoked[A](token: A, max: Duration = Duration.Undefined): Lifecycle.Revoked[A] =
      testKit.expectMsgPF (max = max) {
        case x: Lifecycle.Revoked[_] if x.token == token =>
          x.asInstanceOf[Lifecycle.Revoked[A]]
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

  def withFixture(test: Fixture => Any)(implicit poolSizeRange: PoolSizeRange, leaseTimeout: LeaseTimeout = LeaseTimeout.Fixed(30.seconds)) {

    val fixture = new Fixture(poolSizeRange, leaseTimeout)
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
        expectDead(Tokens(1))
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

        expectMsg(Lifecycle.TokenRequest, max = (1 second))

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
        expectMsg(Lifecycle.TokenRequest, max = tokenRetryInterval*3/2)

      }
    }

  }

  describe ("A server with a 0.2-second initial and 1-second subsequent lease timeout") {

    implicit val poolSizeRange: PoolSizeRange = 0 to 1
    implicit val leaseTimeout: LeaseTimeout =
      LeaseTimeout.FirstAndSubsequent(first = (0.2 seconds), subsequent = (1 second))

    it ("should revoke a lease not acknowledged within 0.2 seconds") {
      withFixture { f => import f._

        server ! Lease.Request
        expectMsg(Lifecycle.TokenRequest)

        server ! Lifecycle.NewToken(Tokens(1))
        expectLease(Tokens(1))

        expectRevoked(Tokens(1), max = (0.3 seconds))

      }
    }

    it ("""should revoke a lease that was immediately (but not subsequently) acknowledged,
        |  after 1 second""".stripMargin) {
      withFixture { f => import f._

        server ! Lease.Request
        expectMsg(Lifecycle.TokenRequest)

        server ! Lifecycle.NewToken(Tokens(1))
        val lease = expectLease(Tokens(1))

        lease.acknowledge()
        expectNoMsg(max = (0.8 seconds))

        expectRevoked(Tokens(1), max = (0.3 seconds))

      }
    }

    it ("should not revoke a lease that is acknowledged every half-second then released") {
      withFixture { f => import f._

        server ! Lease.Request
        expectMsg(Lifecycle.TokenRequest)

        server ! Lifecycle.NewToken(Tokens(1))
        val lease = expectLease(Tokens(1))

        lease.acknowledge()

        Thread.sleep(500)
        lease.acknowledge()

        Thread.sleep(500)
        lease.acknowledge()

        Thread.sleep(500)
        lease.acknowledge()

        Thread.sleep(500)
        lease.release()

        expectNoMsg(max = (2 seconds))
      }
    }

  }

  describe ("A server with pool size 1") {

    implicit val poolSizeRange: PoolSizeRange = 1 to 1
    implicit val leaseTimeout: LeaseTimeout = (0.2 seconds)

    it ("should request a new token if the only token is revoked") {
      withFixture { f => import f._

        expectMsg(Lifecycle.TokenRequest)
        server ! Lifecycle.NewToken(Tokens(1))

        server ! Lease.Request
        expectLease(Tokens(1))

        expectRevoked(Tokens(1), max = (1 second))
        expectMsg(Lifecycle.TokenRequest)

      }
    }

  }

  describe ("A server with pool size 2") {

    implicit val poolSizeRange: PoolSizeRange = 2 to 2

    it ("should refill itself when both of its tokens die") {
      withFixture { f => import f._

        expectMsg(Lifecycle.TokenRequest)
        server ! Lifecycle.NewToken(Tokens(1))

        expectMsg(Lifecycle.TokenRequest)
        server ! Lifecycle.NewToken(Tokens(2))

        Tokens(1).alive = false
        Tokens(2).alive = false
        server ! Lease.Request

        expectDead(Tokens(2))
        expectDead(Tokens(1))
        expectMsg(Lifecycle.TokenRequest)

        server ! Lifecycle.NewToken(Tokens(3))
        expectLease(Tokens(3))
        expectMsg(Lifecycle.TokenRequest)

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
