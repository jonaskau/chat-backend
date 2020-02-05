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
  case class GetMessages(chatId: ObjectId, amount: Int, untilDate: Long)
  case class InsertMessage(chatId: ObjectId, author: String, message: String)
}
class MessagesActor extends Actor {
  import MessagesActor._
  import org.mongodb.scala.model.Filters._
  import org.mongodb.scala.model.Sorts._

  val messagesCollection: MongoCollection[Message] =
    DatabaseService.chatroomDB.getCollection("messages")

  override def receive: Receive = receiveMethod(FutureHandlerBlocking)

  def receiveMethod(futureHandler: FutureHandler): Receive = {
    case GetMessages(chatId, amount, untilDate) =>
      val senderRef = sender()
      val messagesFuture = messagesCollection
        .find(and(equal("chatId", chatId), lt("date", untilDate)))
        .sort(descending("date"))
        .limit(amount)
        .toFuture()
      futureHandler.GetSeq[Message](messagesFuture, senderRef ! _)
    case InsertMessage(chatId, author, message) =>
      val senderRef = sender()
      val currentMessage = Message(chatId, author, message)
      val insertedFuture = messagesCollection.insertOne(currentMessage).head()
      futureHandler.WriteObject(insertedFuture, senderRef ! _)
    case Blocking(shouldBlock) =>
      futureHandler.switchActorState(shouldBlock, receiveMethod, context)
  }
}
