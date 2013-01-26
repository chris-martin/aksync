package org.codeswarm.aksync

trait Lifecycle[A] {

  /** Fills the role of a token lifecycle actor. The actor must reply to `TokenRequest` messages,
    * and may also optionally handle `Destroy` messages.
    */
  def actor: akka.actor.ActorRef

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
    * opportunity to clean up resources. Handling this message is optional.
    */
  case class Destroy[A](token: A)

}