package org.codeswarm.aksync

import akka.actor._
import collection.mutable._
import org.scalatest.BeforeAndAfter
import com.typesafe.config.ConfigFactory

/** A quick introductory example of how to create and use a basic `Server`.
  */
class VerySimpleExample extends org.scalatest.FreeSpec with BeforeAndAfter {

  var system: ActorSystem = null

  before {
    system = ActorSystem("test", ConfigFactory.parseString("akka.daemonic = on"))
  }

  after {
    system.shutdown()
  }

  "Very simple example" in {

    /** A dumb example of a token class. In an actual application this would be something
      * nontrivial with blocking methods. In this case it's just an integer identifier
      * so we can tell which token is which.
      */
    case class ExampleToken(id: Int)

    /** Reassigning `Lease.Request` just gives a nicer API to clients.
      */
    val ExampleTokenRequest = Lease.Request

    /** Extending `LeaseWrapper` makes it easier for clients to receive lease messages,
      * because type erasure means you can't pattern match on `Lease[ExampleToken]`.
      */
    case class ExampleTokenLease(lease: Lease[ExampleToken]) extends LeaseWrapper[ExampleToken]

    /** Trivial lifecycle definition just creates new tokens with incrementing `id`s.
      */
    val lifecycle = new BlockingLifecycle[ExampleToken] {
      val ids = (1 to Int.MaxValue).iterator
      def create(): ExampleToken = ExampleToken(ids.next())
    }

    val server = system.actorOf(Props(new Server[ExampleToken](

      lifecycle = lifecycle,

      /** Our server has a maximum of 2 tokens in the pool.
        */
      poolSizeRange = 0 to 2,

      /** Send `ExampleTokenLease` messages instead of `Lease[ExampleToken]`.
        */
      leaseTransform = ExampleTokenLease(_)

    )), "server")

    val output = new ArrayBuffer[String] with SynchronizedBuffer[String]

    class Client extends Actor {

      /** When the client starts, it requests a token lease from the server.
        */
      override def preStart() {
        server ! ExampleTokenRequest
      }

      def receive = {

        /** When the server has a token available, it replies with a lease.
          */
        case lease: ExampleTokenLease =>

          /** This control structure sends an ackowledgement to the server when the block
            * is entered, and releases the lease back to the server when the block exits.
            */
          lease { token =>
            output += "%s acquires token %d".format(self.path.name, token.id)
            Thread sleep 2000
            output += "%s releases token %d".format(self.path.name, token.id)
          }
          context stop self
      }
    }

    for (id <- 'A' to 'C') {
      system.actorOf(Props(new Client()), "Client_%s".format(id))
      Thread.sleep(100)
    }

    /** Give the actors enough time to finish.
      */
    Thread sleep 5000

    output.foreach(info(_))

    /** When Client C makes its request, there are no tokens available.
      * After Client A releases its token, it is leased to Client C.
      */
    assert(output.toList == List(
      "Client_A acquires token 1",
      "Client_B acquires token 2",
      "Client_A releases token 1",
      "Client_C acquires token 1",
      "Client_B releases token 2",
      "Client_C releases token 1"
    ))

  }

}