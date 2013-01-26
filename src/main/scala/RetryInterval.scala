package org.codeswarm.aksync

import concurrent.duration.{Duration, DurationInt, FiniteDuration}

trait RetryInterval {

  def apply(i: Int): FiniteDuration

}

object RetryInterval {

  case class ExponentialBackoff(min: FiniteDuration = 1.second,
      max: FiniteDuration = 15.seconds, base: Double = 1.2) extends RetryInterval {

    def apply(i: Int): FiniteDuration = List(
      Duration.fromNanos(min.toNanos * math.pow(base, i - 1).toLong),
      max
    ).min

  }

  case class Fixed(value: FiniteDuration) extends RetryInterval {

    def apply(i: Int): FiniteDuration = value

  }

  implicit def fixed(value: FiniteDuration): Fixed = Fixed(value)

}