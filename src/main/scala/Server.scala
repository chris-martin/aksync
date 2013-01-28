package org.codeswarm.aksync

import akka.actor.{Actor, ActorLogging, ActorRef, Cancellable}
import collection.mutable.{Queue, HashMap, Stack}
import concurrent.duration.{Duration, DurationInt, FiniteDuration}

import Server._

class Server[A](lifecycle: Lifecycle[A], poolSizeRange: PoolSizeRange = 2 to 8,
    leaseTimeout: Duration = 30.seconds,
    tokenRetryInterval: RetryInterval = RetryInterval.ExponentialBackoff())
    (implicit manifestA: Manifest[A]) extends Actor with ActorLogging {

  private val clients = Queue[ActorRef]()
  private val tokens = Stack[A]()
  private val leases = HashMap[Lease[A], LeaseState]()
  private var tokenCreationState: TokenCreationState = TokenCreationState.NotDoingAnything

  override def preStart() {
    self ! Internal.MaybeRequestToken
  }

  def receive = {

    case Lease.Request =>

      log debug "Received Lease.Request"

      if (poolSizeRange.isZero) {
        /* If the pool size is fixed at 0, this server will never do anything,
           so there is no point in enqueuing any requests. */
      } else {

        clients enqueue sender

        self ! Internal.MaybeIssueLeases
        self ! Internal.MaybeRequestToken
      }

    case Lease.Acknowledge(lease: Lease[A]) =>

      log debug "Received Lease.Acknowledge"

      leases.get(lease) match {
        case Some(state) => state.ack()
        case None => log warning "Received Acknowledge for unknown lease from %s".format(sender)
      }

    case Lease.Release(lease: Lease[A]) =>

      log debug "Received Lease.Release"

      leases.remove(lease) match {
        case Some(state) => tokens push lease.token
        case None => log warning "Received Release for unknown lease from %s".format(sender)
      }

      self ! Internal.MaybeIssueLeases

    case Internal.MaybeIssueLeases =>

      createLease() match {

        case Some(lease) =>

          log debug "Issuing %s".format(lease)

          leases += lease -> new LeaseState(lease)
          lease.client ! lease
          self ! Internal.MaybeIssueLeases

        case None =>
      }

      self ! Internal.MaybeRequestToken

    case Internal.MaybeExpire(lease: Lease[A], id: Int) =>

      leases.get(lease) foreach { state =>
        if (state.isExpired(id)) {
          log warning "Expiring a lease (acks: %d) that was issued to %s".format(id, lease.client)
          leases -= lease
        }
      }

    case Internal.MaybeRequestToken =>

      if (tokenCreationState == TokenCreationState.NotDoingAnything) {

        val size = tokens.size + leases.size

        val needsAnotherToken = (poolSizeRange requiresMoreThan size) ||
          (clients.nonEmpty && (poolSizeRange allowsMoreThan size))

        if (needsAnotherToken) {
          tokenCreationState = TokenCreationState.AnticipatingNewToken()
          self ! Internal.RequestToken
        }
      }

    case Internal.RequestToken =>

      log debug "Sending Lifecycle.TokenRequest"

      lifecycle.actor ! Lifecycle.TokenRequest

    case Lifecycle.NewToken(token) =>

      log debug "Received Lifecycle.NewToken"

      if (!manifestA.runtimeClass.isAssignableFrom(token.getClass)) {
        log warning "Received NewToken of incorrect type %s".format(token.getClass)
      } else {
        tokenCreationState match {
          case TokenCreationState.NotDoingAnything =>
            log warning "Received unexpected NewToken from %s".format(sender)
          case _: TokenCreationState.AnticipatingNewToken =>
            tokenCreationState = TokenCreationState.NotDoingAnything
            tokens push token.asInstanceOf[A]
            self ! Internal.MaybeIssueLeases
            self ! Internal.MaybeRequestToken
        }
      }

    case Lifecycle.TokenUnavailable =>

      log debug("Received Lifecycle.TokenUnavailable")

      tokenCreationState match {
        case TokenCreationState.NotDoingAnything =>
          log warning "Received unexpected TokenUnavailable from %s".format(sender)
        case x: TokenCreationState.AnticipatingNewToken =>
          tokenCreationState = x.fail
          schedule(tokenRetryInterval(tokenCreationState.nrOfFails), self, Internal.RequestToken)
      }

    case m =>

      log warning "Received unrecognized message: %s".format(m)

  }

  private def schedule(delay: FiniteDuration, receiver: ActorRef, message: Any): Cancellable = {
    val s = context.system; import s._
    s.scheduler.scheduleOnce(delay, receiver, message)
  }

  private def createLease(): Option[Lease[A]] = {

    // Remove terminated requestors to avoid wasting time issuing a lease to a dead actor.
    // This does not guarantee that it will never happen (there is a race condition), but
    // it's unlikely.
    while (clients.headOption.exists(_.isTerminated)) {
      log debug "Removing dead actor"
      clients.dequeue()
    }

    // No one is currently waiting for a lease.
    if (clients.isEmpty) {
      log debug "There are no requestors waiting"
      return None
    }

    // Remove dead tokens as a best effort toward avoiding giving a client a dead token
    // (for example, if the token is a database connection that has timed out).
    while (tokens.headOption.exists(lifecycle.isDead(_))) {
      log debug "Removing dead token"
      lifecycle.actor ! Lifecycle.Destroy(tokens.pop())
    }

    // There are no free connections available.
    if (tokens.isEmpty) {
      log debug "There are no tokens available for lease"
      return None
    }

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
          val cancellable = schedule(t, self, Internal.MaybeExpire(lease, id))
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
    case object MaybeIssueLeases
    case class MaybeExpire(lease: Lease[_], timerId: Int)
    case object MaybeRequestToken
    case object RequestToken
  }

  /** An enumeration of where the server is in its conversation with the lifecycle actor
    * in regards to creating new tokens.
    */
  private trait TokenCreationState {

    def nrOfFails: Int = 0
  }

  private object TokenCreationState {

    /** Nothing is going on. We're not interested in getting new tokens, and the lifecycle
      * actor shouldn't be doing anything.
      */
    case object NotDoingAnything extends TokenCreationState

    /** A token has been requested, and we are waiting for the lifecycle actor to reply.
      */
    case class AnticipatingNewToken(override val nrOfFails: Int = 0) extends TokenCreationState {

      def fail = AnticipatingNewToken(nrOfFails + 1)
    }

  }

}