package org.codeswarm.aksync

import akka.actor._

/** A `Lifecycle` defines how tokens are born, how they die, and what happens after they die.
  * You can either extend this trait directly, or extend [[BlockingLifecycle]] if it is
  * sufficient.
  */
trait Lifecycle[Token] extends LivenessCheck[Token] {

  /** Returns an actor which fills the role of a token lifecycle actor. The actor must reply to
    * `TokenRequest` messages, and may also optionally handle `Destroy` messages. See the
    * [[Lifecycle$ Lifecyle companion object]] for a more complete description of the messages
    * that this actor is expected to deal with.
    *
    * This method may use the provided `context` to create an actor that will be supervised by
    * the `Server`. Alternately, it may ignore the `context` and return an existing actor. The
    * server only calls this method once.
    */
  def actor(implicit context: akka.actor.ActorContext): akka.actor.ActorRef

}

object Lifecycle {

  /** A token lifecycle actor '''must''' reply to a `TokenRequest` with either `NewToken` or
    *  `TokenUnavailable`.
    */
  case object TokenRequest

  /** Sending a `NewToken` message to a `Server` adds a token to the pool's collection
    * of managed tokens.
    */
  case class NewToken[Token](token: Token)

  /** Indicates that a token cannot be created at this time. For example, if the token is
    * a network connection, perhaps the network is down.
    */
  case object TokenUnavailable

  /** The `Destroy` message is sent by the server to the token lifecycle actor after a token is
    * removed from the pool, either as a result of inactivity or lease revocation. This is an
    * opportunity to clean up resources. Handling these messages is optional.
    */
  sealed trait Destroy[Token] {
    def token: Token
  }

  /** Indicates that the token was been leased to a client who never returned it.
    * The token is removed from the pool in case the misbehaving client is still using it.
    */
  case class Revoked[Token](token: Token) extends Destroy[Token]

  /** Indicates that the token has been removed from the pool because it is "dead" as
    * determined by the `Lifecycle#isDead(Token)`.
    */
  case class Dead[Token](token: Token) extends Destroy[Token]

}

/** Extending this class is the simplest way to implement `Lifecycle`. It is sufficient
  * if token creation and destruction are fast.
  */
abstract class BlockingLifecycle[Token] extends Lifecycle[Token]
    with BlockingCreateAndDestroy[Token] {

  def actor(implicit context: ActorContext): ActorRef =
    context.actorOf(Props(new BlockingCreateAndDestroyActor(this)))

}

/** The simplest possible lifecycle. Since every token is the same object, a `Server` with a
  * unit lifecycle merely serves as a semaphore.
  */
object UnitLifecycle extends BlockingLifecycle[Unit] {
  def create() {}
}

/** Determines whether a token is alive or dead. The general idea is that a token is
  * alive when it is first created, and may die at some point if something bad happens
  * to it (such as if it is a database connection that times out from inactivity).
  * This check is performed by the `Server` actor in a blocking manner, so it should be fast.
  */
trait LivenessCheck[Token] {
  def isAlive(token: Token): Boolean = true
  final def isDead(token: Token): Boolean = !isAlive(token)
}

/** Specifies the behavior of a `BlockingCreateAndDestroyActor`. These methods are invoked by the
  * actor in a blocking manner, so they should be fast. If lifecycle management actions
  * require more time, you should implement your own lifecycle `Actor`.
  */
trait BlockingCreateAndDestroy[Token] {

  /** Invoked when the lifecycle actor receives a `Lifecycle.TokenRequest` message.
    * Should create a new token for the pool when one is requested. If this method returns
    * successfully, a `Lifecycle.NewToken` is sent to the server. If this method throws
    * an exception, `Lifecycle.TokenUnavailable` is sent instead.
    */
  def create(): Token

  /** Invoked when the lifecycle actor receives a `Lifecycle.Revoked` message.
    */
  def handleRevocation(token: Token) { }

  /** Invoked when the lifecycle actor receives a `Lifecycle.Dead` message.
    */
  def handleDeath(token: Token) { }

}

/** A simple implementation of a lifecycle actor.
  */
class BlockingCreateAndDestroyActor[Token](spec: BlockingCreateAndDestroy[Token])
    extends Actor with ActorLogging {

  def receive = {

    case Lifecycle.TokenRequest =>
      sender ! (
        try {
          Lifecycle.NewToken(spec.create())
        } catch {
          case e: Throwable =>
            log error (e, "Token creation failed")
            Lifecycle.TokenUnavailable
        }
      )

    case m: Lifecycle.Revoked[_] =>
      try {
        spec.handleRevocation(m.token.asInstanceOf[Token])
      } catch {
        case e: Throwable => log error (e, "Revocation handler failed")
      }

    case m: Lifecycle.Dead[_] =>
      try {
        spec.handleDeath(m.token.asInstanceOf[Token])
      } catch {
        case e: Throwable => log error (e, "Death handler failed")
      }

  }

}