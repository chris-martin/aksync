package org.codeswarm.aksync

import akka.actor._, akka.event._

class NoActorLogging extends Actor {

  def receive = {

    case Logging.InitializeLogger(_) =>
      sender ! Logging.LoggerInitialized

  }

}