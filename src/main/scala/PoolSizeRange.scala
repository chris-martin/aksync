package org.codeswarm.aksync

import scala.language.implicitConversions

case class PoolSizeRange(minOption: Option[Int], maxOption: Option[Int]) {

  for (min <- minOption if min < 0)
    throw new IllegalArgumentException("min must be non-negative")

  for (max <- maxOption if max < 0)
    throw new IllegalArgumentException("max must be non-negative")

  for (min <- minOption; max <- maxOption if min > max)
    throw new IllegalArgumentException("min must be less than or equal to max")

  def requiresMoreThan(x: Int): Boolean =
    minOption match {
      case Some(min) => x < min
      case None => false
    }

  def allowsMoreThan(x: Int): Boolean =
    maxOption match {
      case Some(max) => x < max
      case None => true
    }

  def isNonzero: Boolean = allowsMoreThan(0)

  def isZero: Boolean = !isNonzero

}

object PoolSizeRange {

  def apply(min: Integer = null, max: Integer = null): PoolSizeRange =
    PoolSizeRange(Option(min).map(_.toInt), Option(max).map(_.toInt))

  implicit def apply(x: Range): PoolSizeRange = apply(x.min, x.max)

}
