package org.codeswarm

/** `Aksync` provides Akka `Actor`s with mediated access to a pool of resources that
  * require exclusive access. It can be used as a bridge between an actor system and a
  * blocking API.
  *
  * The main class in this package is [[Server]], the `Actor` with which clients needing
  * access to tokens will communicate. A `Server` must be configured with a [[Lifecycle]]
  * that defines how tokens are created (and may also handle their destruction).
  * A client `Actor` sends the `Server` the [[Lease.Request]] message, and the server
  * responds with a [[Lease]] when a token is available.
  *
  * One example use of `Aksync` is as a pool of `java.sql.Connection`s.
  */
package object aksync {}