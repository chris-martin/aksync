package org.codeswarm.aksync

/** When a token is available, the server replies with a `Lease` message to the client
  * who requested it.
  */
trait Lease[+Token] {

  def token: Token

  /** Sends a [[Lease.Acknowledge]] message to the server.
    */
  def acknowledge()

  /** Sends a [[Lease.Release]] message to the server.
    */
  def release()

  /** Encapsulates the control flow of a typical client using the lease. Sends one
    * acknowledgement, tries to execute `f` with the token as its parameter, and
    * definitely releases the lease.
    */
  def apply[A](f: Token => A): A

}

/** Default lease implementation. Equality is referential (`equals` and `hashCode` are not
  * overridden).
  * @param token The thing being leased.
  * @param client Client who sent the `Request` that resulted in this lease.
  * @param server Server from whom the lease was granted, and to whom `Acknowledge` and
  * `Release` messages should be sent.
  */
class StandardLease[+Token] private[aksync] (val token: Token,
    private[aksync] val id: Int,
    private[aksync] val client: akka.actor.ActorRef,
    private[aksync] val server: akka.actor.ActorRef) extends Lease[Token] {

  def acknowledge() {
    server ! Lease.Acknowledge(this)
  }

  def release() {
    server ! Lease.Release(this)
  }

  def apply[B](f: Token => B): B = {
    acknowledge()
    try {
      f(token)
    } finally {
      release()
    }
  }

  override def toString: String = "Lease[%x]".format(id)

}

object Lease {

  /** A client actor asks the server for a token lease by sending the `Request` message.
    */
  case object Request

  /** `Acknowledge` is an affirmation that the client has received and is using a lease.
    * A client should send this message to the server by invoking [[Lease.acknowledge]].
    *
    * == Purpose ==
    * If the server notices that a lease has been out for some amount of time without
    * acknowledgement, the server revokes the lease, under the assumption that no one
    * is using it anymore but someone has failed to release it.
    *
    * == When to acknowledge ==
    * The client should send an acknowledgement immediately upon receiving the lease.
    * If the lease is held for an unusually long time, the client should also periodically
    * re-send further acknowledgements to affirm that the lease is still in active use.
    * There is no harm in acknowledging more often than necessary.
    *
    * == Lease revocation ==
    * When a lease is revoked, its slot in the server's pool opens up, and its token is
    * abandoned by the server, and a [[Lifecycle.Revoked]] message is sent to the
    * [[Lifecycle]] actor.
    */
  case class Acknowledge[Token](lease: Lease[Token])

  /** After the client is done using the token that has been leased to it, it must
    * send a `Release` message to release the token back to the server. This message carries
    * with it the promise that no one other than the server is maintaining a reference to the
    * token that is being returned. Sending this message repeatedly has no effect.
    * A client should send this message to the server by invoking [[Lease.release]].
    */
  case class Release[Token](lease: Lease[Token])

}

trait LeaseWrapper[Token] extends Lease[Token] {

  protected def lease: Lease[Token]

  def token: Token = lease.token
  def acknowledge() { lease acknowledge() }
  def release() { lease release() }
  def apply[A](f: (Token) => A): A = lease apply f

  override def toString: String = lease.toString

}