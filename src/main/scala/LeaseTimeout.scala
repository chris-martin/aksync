package org.codeswarm.aksync

import concurrent.duration.{Duration, DurationInt, FiniteDuration}

/** The amount of time that a lease can survive without being acknowledged. If a lease is
  * not acknowledged or released within this time frame, the server revokes it.
  */
trait LeaseTimeout {

  /** @param i The number of acknowledgements that have occurred. Must be at least 0.
    * @return The expiration timeout duration to impose after the i^th^ acknowledgement.
    * If the duration is infinite, then leases never expire.
    */
  def apply(i: Int): Duration

}

object LeaseTimeout {

  /** Imposes a shorter timeout for the first acknowledgement. Clients are supposed to
    * acknowledge immediately when a lease is issued, so the first acknowledgement ought
    * to be received quickly. After the first acknowledgement, we have at least verified
    * that someone has received the lease, so we can then back off and allow a more lenient
    * expiration period.
    *
    * @param first The initial lease timeout for receiving the first acknowledgement.
    * @param subsequent The lease timeout used for all subsequent acknowledgements after
    * the first.
    */
  case class FirstAndSubsequent(
    first: FiniteDuration = 5.seconds,
    subsequent: Duration = 30.seconds
  ) extends LeaseTimeout {

    def apply(i: Int): Duration = if (i == 0) first else subsequent

  }

  case class Fixed(value: FiniteDuration) extends LeaseTimeout {

    def apply(i: Int): FiniteDuration = value

  }

  implicit def fixed(value: FiniteDuration): Fixed = Fixed(value)

  implicit def fixedDuration(x: Fixed): FiniteDuration = x.value

}