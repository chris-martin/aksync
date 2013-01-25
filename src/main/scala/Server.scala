package org.codeswarm.aksync

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable}
import collection.mutable.{Queue, HashMap, Stack}
import concurrent.duration.{Duration, DurationInt, FiniteDuration}

class Server[A](lifecycle: Lifecycle[A], poolSize: Range = 2 to 8,
    leaseTimeout: Duration = 30.seconds, tokenRequestBackoff: FiniteDuration = 2.seconds)
    (implicit m: Manifest[A]) extends Actor with ActorLogging {

  import Server._

  private val clients = Queue[ActorRef]()
  private val tokens = Stack[A]()
  private val leases = HashMap[Lease[A], LeaseState]()
  private val lifecycleActor = context.actorOf(lifecycle.props)
  private var tokenCreationState: TokenCreationState = TokenCreationState.NotDoingAnything

  def receive = {

    case Lease.Request =>

      clients enqueue sender

      self ! Internal.IssueLeases
      self ! Internal.RequestToken

    case Lease.Acknowledge(lease: Lease[A]) =>

      leases.get(lease) match {
        case Some(state) => state.ack()
        case None => log warning "Received Acknowledge for unknown lease from %s".format(sender)
      }

    case Lease.Release(lease: Lease[A]) =>

      leases.remove(lease) match {
        case Some(state) => tokens push lease.token
        case None => log warning "Received Release for unknown lease from %s".format(sender)
      }

      self ! Internal.IssueLeases

    case Internal.IssueLeases =>

      createLease() match {

        case Some(lease) =>
          leases += lease -> new LeaseState(lease)
          lease.client ! lease
          self ! Internal.IssueLeases
          self ! Internal.RequestToken

        case None =>
      }

    case Internal.Expire(lease: Lease[A], id: Int) =>
      leases.get(lease) foreach { state =>
        if (state.isExpired(id)) {
          log warning "Expiring a lease (acks: %d) that was issued to %s".format(id, lease.client)
          leases -= lease
        }
      }

    case Internal.RequestToken =>

      lazy val totalTokenCount = tokens.size + leases.size
      lazy val poolUndersized = totalTokenCount < poolSize.min
      lazy val poolAtCapacity = totalTokenCount >= poolSize.max
      lazy val clientsAreWaiting = clients.nonEmpty
      lazy val needsAnotherToken = poolUndersized || (clientsAreWaiting && !poolAtCapacity)

      if (tokenCreationState == TokenCreationState.NotDoingAnything && needsAnotherToken) {
        lifecycleActor ! Lifecycle.TokenRequest
        tokenCreationState = TokenCreationState.AnticipatingNewToken
      }

    case Lifecycle.NewToken(token) =>
      if (!m.runtimeClass.isAssignableFrom(token.getClass)) {
        log warning "Received NewToken of incorrect type %s".format(token.getClass)
      } else if (tokenCreationState != TokenCreationState.AnticipatingNewToken) {
        log warning "Received unexpected NewToken"
      } else {
        tokenCreationState = TokenCreationState.NotDoingAnything
        tokens push token.asInstanceOf[A]
        self ! Internal.IssueLeases
        self ! Internal.RequestToken
      }

    case Lifecycle.TokenUnavailable =>
      if (tokenCreationState != TokenCreationState.AnticipatingNewToken) {
        log warning "Received unexpected TokenUnavailable from %s".format(sender)
      } else {
        tokenCreationState = TokenCreationState.BackoffAfterFailure
        schedule(tokenRequestBackoff, Internal.RequestToken)
      }

    case m =>
      log warning "Received unrecognized message: %s".format(m)

  }

  private def schedule(delay: FiniteDuration, message: Any): Cancellable = {
    val s = context.system; import s._
    s.scheduler.scheduleOnce(delay, self, message)
  }

  private def createLease(): Option[Lease[A]] = {

    // Remove terminated requestors to avoid wasting time issuing a lease to a dead actor.
    // This does not guarantee that it will never happen (there is a race condition), but
    // it's unlikely.
    while (clients.headOption.exists(_.isTerminated))
      clients.dequeue()

    // No one is currently waiting for a lease.
    if (clients.isEmpty)
      return None

    // Remove dead tokens as a best effort toward avoiding giving a client a dead token
    // (for example, if the token is a database connection that has timed out).
    while (tokens.headOption.exists(lifecycle.isDead(_)))
      lifecycleActor ! Lifecycle.Destroy(tokens.pop())

    // There are no free connections available.
    if (tokens.isEmpty)
      return None

    // Create a new lease.
    Some(new Lease(tokens.pop(), client = clients.dequeue(), server = self))

  }

  private class LeaseState(lease: Lease[A]) {

    private val ids = (0 until Int.MaxValue).iterator
    private case class Timer(cancellable: Cancellable, id: Int)
    private var timer: Option[Timer] = None

    ack()

    def ack() {

      timer.foreach(_.cancellable.cancel())

      timer = leaseTimeout match {

        case t: FiniteDuration =>
          val id = ids.next()
          val cancellable = schedule(t, Internal.Expire(lease, id))
          Some(new Timer(cancellable, id))

        case _ => None
      }
    }

    def isExpired(id: Int): Boolean =
      timer match {
        case Some(Timer(_, currentId)) => id == currentId
        case None => false
      }

  }

}
object Server {

  private object Internal {
    case object IssueLeases
    case class Expire(lease: Lease[_], timerId: Int)
    case object RequestToken
  }

  /** An enumeration of where the server is in its conversation with the lifecycle actor
    * in regards to creating new tokens.
    */
  private trait TokenCreationState

  private object TokenCreationState {

    /** Nothing is going on. We're not interested in getting new tokens, and the lifecycle
      * actor shouldn't be doing anything.
      */
    object NotDoingAnything extends TokenCreationState

    /** A token has been requested, and we are waiting for the lifecycle actor to reply.
      */
    object AnticipatingNewToken extends TokenCreationState

    /** The lifecycle actor has replied with failure, so we are pausing before retrying
      * with another request.
      */
    object BackoffAfterFailure extends TokenCreationState

  }

}