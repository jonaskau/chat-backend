package chatroom.database

import akka.actor.Actor
import chatroom.database.FutureHandler.Blocking
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.ObjectId
import org.mongodb.scala.model.Filters.equal

object Message {
  def apply(chatId: ObjectId, author: String, message: String): Message =
    Message(new ObjectId(), chatId, System.currentTimeMillis(), author, message)
}
case class Message(_id: ObjectId, chatId: ObjectId, date: Long, author: String, message: String)

object MessagesActor {
  case class GetAllMessages(username: String)
  case class InsertMessage(chatId: ObjectId, author: String, message: String)
}
class MessagesActor extends Actor {
  import MessagesActor._

  val messagesCollection: MongoCollection[Message] =
    DatabaseService.chatroomDB.getCollection("messages")

  override def receive: Receive = receiveMethod(FutureHandlerBlocking)

  def receiveMethod(futureHandler: FutureHandler): Receive = {
    case GetAllMessages(username) =>
      val senderRef = sender()
      val messagesOptionFuture = messagesCollection.find(equal("author", username)).toFuture()
      futureHandler.GetSeq[Message](messagesOptionFuture, senderRef ! _)
    case InsertMessage(chatId, author, message) =>
      val senderRef = sender()
      val currentMessage = Message(chatId, author, message)
      val insertedFuture = messagesCollection.insertOne(currentMessage).head()
      futureHandler.WriteObject(insertedFuture, senderRef ! _)
    case Blocking(shouldBlock) =>
      futureHandler.switchActorState(shouldBlock, receiveMethod, context)
  }
}
