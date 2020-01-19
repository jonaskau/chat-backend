package chatroom.database

import akka.actor.Actor
import chatroom.database.FutureHandler.Blocking
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.ObjectId

object Chat {
  def apply(name: String, users: List[String]): Chat =
    Chat(new ObjectId(), name, users)
}
case class Chat(_id: ObjectId, name: String, users: List[String])

object ChatsActor {
  case object GetAllChats
  case class GetChatByName(name: String)
  case class InsertChat(chat: Chat)
  case class AddUsersToChat(username: String, chatId: ObjectId, users: List[String])
}
class ChatsActor extends Actor{
  import ChatsActor._
  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Updates._

  val chatsCollection: MongoCollection[Chat] =
    DatabaseService.chatroomDB.getCollection("chats")

  override def receive: Receive = receiveMethod(FutureHandlerBlocking)

  def receiveMethod(futureHandler: FutureHandler): Receive = {
    case GetAllChats =>
      val senderRef = sender()
      val chatsOptionFuture = chatsCollection.find().toFuture()
      futureHandler.GetSeq[Chat](chatsOptionFuture, senderRef ! _)
    case GetChatByName(name) =>
      val senderRef = sender()
      val chatOptionFuture = chatsCollection.find(equal("name", name)).headOption()
      futureHandler.GetOption[Chat](chatOptionFuture, senderRef ! _)
    case AddUsersToChat(username, chatId, users) =>
      val senderRef = sender()
      val chatOptionFuture = chatsCollection.find(equal("_id", chatId)).headOption()
      futureHandler.GetOption[Chat](chatOptionFuture, {
        case Some(chat) =>
          if (chat.users.contains(username)) {
            val updatedFuture = chatsCollection
              .updateOne(equal("_id", chatId), addEachToSet("users", users:_*))
              .head()
            futureHandler.WriteObject(updatedFuture, senderRef ! _)
          } else {
            sender() ! false
          }
        case _ => sender() ! false
      })
    case InsertChat(chat) =>
      val senderRef = sender()
      val insertedFuture = chatsCollection.insertOne(chat).head()
      futureHandler.WriteObject(insertedFuture, senderRef ! _)
    case Blocking(shouldBlock) =>
      futureHandler.switchActorState(shouldBlock, receiveMethod, context)
  }
}
