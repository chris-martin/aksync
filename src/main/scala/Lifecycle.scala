package org.codeswarm.aksync

trait Lifecycle[A] {

  /** Returns an actor which fills the role of a token lifecycle actor. The actor must reply to
    * `TokenRequest` messages, and may also optionally handle `Destroy` messages.
    *
    * This method may use the provided `context` to create an actor that will be supervised by the `Server`.
    * Alternately, it may ignore the `context` and return an existing actor. The server only calls this method once.
    */
  def actor(implicit context: akka.actor.ActorContext): akka.actor.ActorRef

  def isAlive(a: A): Boolean = true
  final def isDead(a: A): Boolean = !isAlive(a)

}

object Lifecycle {

  /** A token lifecycle actor '''must''' reply to a `TokenRequest` with either `NewToken` or
    *  `TokenUnavailable`.
    */
  case object TokenRequest

  /** Sending a `NewToken` message to a `Server` adds a token to the pool's collection
    * of managed tokens.
    */
  case class NewToken[A](token: A)

  /** Indicates that a token cannot be created at this time. For example, if the token is
    * a network connection, perhaps the network is down.
    */
  case object TokenUnavailable

  /** The `Destroy` message is sent by the server to the token lifecycle actor after a token is
    * removed from the pool, either as a result of inactivity or lease revocation. This is an
    * opportunity to clean up resources. Handling these messages is optional.
    */
  sealed trait Destroy[A] {
    def token: A
  }

  /** Indicates that the token was been leased to a client who never returned it.
    * The token is removed from the pool in case the misbehaving client is still using it.
    */
  case class Revoked[A](token: A) extends Destroy[A]

  /** Indicates that the token has been removed from the pool because it is "dead" as
    * determined by the `Lifecycle#isDead(A)`.
    */
  case class Dead[A](token: A) extends Destroy[A]

}