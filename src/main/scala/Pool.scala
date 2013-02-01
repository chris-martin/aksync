package org.codeswarm.aksync

import scala.collection.mutable

/** An ordered collection of `A`. This is designed primarily to be an abstraction over
  * [[Pool.Stack]] and [[Pool.Queue]] so that the implementation may use either a FIFO
  * or FILO strategy, but any other strategy is fine too.
  */
trait Pool[A] {

  /** And one item to the pool.
    */
  def add(a: A)

  /** Remove and return one item from the pool.
    *
    * @throws NoSuchElementException If the pool is empty.
    */
  def remove(): A

  /** Returns the item that would be removed by `remove()`,
    * or `None` if the pool is empty.
    */
  def peek: Option[A]

  /** The number of items in the pool.
    */
  def size: Int

  /** Whether the pool size is zero.
    */
  def isEmpty: Boolean

  /** Whether the pool size is non-zero.
    */
  def nonEmpty: Boolean = !isEmpty

}

object Pool {

  /** First-in-first-out.
    */
  class Queue[A](queue: mutable.Queue[A] = mutable.Queue[A]()) extends Pool[A] {
    def add(a: A) { queue.enqueue(a) }
    def remove(): A = queue.dequeue()
    def peek: Option[A] = queue.headOption
    def size: Int = queue.size
    def isEmpty: Boolean = queue.isEmpty
  }

  /** First-in-last-out.
    */
  class Stack[A](stack: mutable.Stack[A] = mutable.Stack[A]()) extends Pool[A] {
    def add(a: A) { stack.push(a) }
    def remove(): A = stack.pop()
    def peek: Option[A] = stack.headOption
    def size: Int = stack.size
    def isEmpty: Boolean = stack.isEmpty
  }

}