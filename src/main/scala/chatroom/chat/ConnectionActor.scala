package chatroom.chat

import akka.actor.{Actor, ActorRef, PoisonPill}

object ConnectionActor {
  sealed trait ConnectionEvent
  case class AddChatRoomActor(chatId: String, chatRoomActor: ActorRef) extends ConnectionEvent
  case class AddChatRoomActorAndSendUserOnline(username: String, chatId: String, chatRoomActor: ActorRef) extends ConnectionEvent
  case class SendUserOnline(username: String, userActor: ActorRef) extends ConnectionEvent
  case class SendUserOffline(username: String) extends ConnectionEvent
  case class SendIncomingMessage(chatId: String, sender: String, message: String) extends ConnectionEvent
}
class ConnectionActor extends Actor{
  import ChatRoomActor._
  import ConnectionActor._

  var chatRoomActors: Map[String, ActorRef] = Map.empty[String, ActorRef]
  var userActorRef: ActorRef = _

  override def receive: Receive = {
    case AddChatRoomActor(chatId, chatRoomActor) =>
      chatRoomActors += chatId -> chatRoomActor
      println(chatId, chatRoomActor)
    case AddChatRoomActorAndSendUserOnline(username, chatId, chatRoomActor) =>
      chatRoomActors += chatId -> chatRoomActor
      chatRoomActors(chatId) ! UserOnline(username, userActorRef)
    case SendUserOnline(username, userActor) =>
      userActorRef = userActor
      chatRoomActors.foreach(_._2 ! UserOnline(username, userActor))
    case SendUserOffline(username) =>
      chatRoomActors.foreach(_._2 ! UserOffline(username))
      ChatRoomsAndConnections.removeConnection(username)
      self ! PoisonPill
    case SendIncomingMessage(chatId, sender, message) =>
      chatRoomActors(chatId) ! IncomingMessage(sender, message)
  }
}
