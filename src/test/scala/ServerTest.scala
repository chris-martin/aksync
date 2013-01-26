package org.codeswarm.aksync

import akka.testkit.{ImplicitSender, TestActorRef, TestKit}
import akka.actor.{Props, ActorSystem, ActorRef}
import org.scalatest.{BeforeAndAfterAll, FunSuite}
import concurrent.duration.DurationDouble
import com.typesafe.config.ConfigFactory

class ServerTest(_system : ActorSystem) extends TestKit(_system) with ImplicitSender
    with FunSuite with BeforeAndAfterAll {

  import ServerTest._

  def this() = this(ActorSystem(
    "ServerTest",
    ConfigFactory.load(ConfigFactory.parseString("""
      akka {
        loglevel = DEBUG
      }
    """))
  ))

  override def afterAll() {
    system.shutdown()
  }

  val tokenRetryInterval = (0.25 seconds)
  val fast = (0.05 seconds)

  test("test") {

    val server = TestActorRef(Props(new Server[Token](
      lifecycle = new Lifecycle[Token]() {
        override def isAlive(a: Token): Boolean = a.alive
        override def actor: ActorRef = testActor
      },
      poolSize = 2 to 4,
      tokenRetryInterval = tokenRetryInterval
    )), "server")

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

object ServerTest {

  class Token(val id: Int, var alive: Boolean = true)

}