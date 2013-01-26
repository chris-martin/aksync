package org.codeswarm.aksync

case class PoolSizeRange(minOption: Option[Int], maxOption: Option[Int]) {

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

}

object PoolSizeRange {

  def apply(min: Integer = null, max: Integer = null): PoolSizeRange =
    PoolSizeRange(Option(min).map(_.toInt), Option(max).map(_.toInt))

  implicit def apply(x: Range): PoolSizeRange = apply(x.min, x.max)

}
