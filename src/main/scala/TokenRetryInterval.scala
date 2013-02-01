package org.codeswarm.aksync

import scala.concurrent.duration._
import scala.language.implicitConversions

trait TokenRetryInterval {

  /** @param i The number of consecutive failures that have occurred. Must be at least 1.
    * @return The amount of time to wait after the i^th^ failure.
    */
  def apply(i: Int): FiniteDuration

}

object TokenRetryInterval {

  /** @param min The amount of time to wait after the first failure.
    * @param max A maximum duration. The delay will grow up to this quantity and then
    * remain at the maximum value indefinitely.
    * @param base The base of the exponent used to calculate the delay's growth as the
    * number of failures increases. Must be at least 1. If the base is exactly 1, then
    * the delay will remain constant at the `min` value. If the base is 2, the delay
    * doubles with each failure.
    */
  case class ExponentialBackoff(min: FiniteDuration = 1.second,
      max: FiniteDuration = 15.seconds, base: Double = 1.2) extends TokenRetryInterval {

    if (base < 1) throw new IllegalArgumentException("base = %f < 1".format(base))

    def apply(i: Int): FiniteDuration = {

      if (i < 1) throw new IllegalArgumentException("i = %d < 0".format(i))

      List(
        Duration.fromNanos(min.toNanos * math.pow(base, i - 1).toLong),
        max
      ).min
    }

  }

  case class Fixed(value: FiniteDuration) extends TokenRetryInterval {

    def apply(i: Int): FiniteDuration = value

  }

  implicit def fixed(value: FiniteDuration): Fixed = Fixed(value)

  implicit def fixedDuration(x: Fixed): FiniteDuration = x.value

}