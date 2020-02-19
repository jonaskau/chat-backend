package chatroom.database

import akka.actor.Actor
import chatroom.database.FutureHandler.Blocking
import org.mongodb.scala.MongoCollection
import org.mongodb.scala.bson.ObjectId

object User {
  def apply(username: String, password: String): User =
    User(new ObjectId(), username, password, "basic")
}
case class User(_id: ObjectId, username: String, password: String, scope: String)

object UsersActor {
  case class GetUserByUsername(username: String)
  case class GetUsersByPrefix(usernamePrefix: String)
  case class ExtractActualUsernames(username: List[String])
  case class InsertUser(username: String, password: String)
}
class UsersActor extends Actor{
  import UsersActor._
  import org.mongodb.scala.model.Filters._

  val usersCollection: MongoCollection[User] =
    DatabaseService.chatroomDB.getCollection("users")

  override def receive: Receive = receiveMethod(FutureHandlerBlocking)

  def receiveMethod(futureHandler: FutureHandler): Receive = {
    case GetUserByUsername(username) =>
      val senderRef = sender()
      val userOptionFuture = usersCollection.find(equal("username", username)).headOption()
      futureHandler.GetOption[User](userOptionFuture, senderRef ! _)
    case GetUsersByPrefix(usernamePrefix) =>
      val senderRef = sender()
      val usersFuture = usersCollection.find(regex("username", usernamePrefix)).toFuture()
      futureHandler.GetSeq[User](usersFuture, users => {
        senderRef ! users.filter(_.username != "admin")
      })
    case ExtractActualUsernames(usernameList) =>
      val senderRef = sender()
      val usersFuture = usersCollection.find(in("username", usernameList:_*)).toFuture()
      futureHandler.GetSeq[User](usersFuture, senderRef  ! _)
    case InsertUser(username, password) =>
      val senderRef = sender()
      val user = User(username, password)
      val insertedFuture = usersCollection.insertOne(user).head()
      futureHandler.WriteObject(insertedFuture, senderRef ! _)
    case Blocking(shouldBlock) =>
      futureHandler.switchActorState(shouldBlock, receiveMethod, context)
  }
}