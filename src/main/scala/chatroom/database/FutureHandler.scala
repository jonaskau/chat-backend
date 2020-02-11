package chatroom.database

import java.util.concurrent.TimeUnit

import akka.actor.{Actor, ActorContext, ActorRef}

import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success}

object FutureHandler {
  case class Blocking(shouldBlock: Boolean)
}
trait FutureHandler {
  def WriteObject(future: Future[Object], onComplete: Boolean => Unit): Unit
  def GetOption[T](future: Future[Option[T]], onComplete: Option[T] => Unit): Unit
  def GetSeq[T](future: Future[Seq[T]], onComplete: Seq[T] => Unit): Unit
  def switchActorState(shouldBlock: Boolean,
                       receiveMethod: FutureHandler => Actor.Receive,
                       context: ActorContext): Unit = {
    val actorName = context.self.path.name
    if (shouldBlock && this.equals(FutureHandlerNonBlocking)) {
      context.become(receiveMethod(FutureHandlerBlocking))
      println(s"$actorName -> Blocking")
    } else if (!shouldBlock && this.equals(FutureHandlerBlocking)) {
      context.become(receiveMethod(FutureHandlerNonBlocking))
      println(s"$actorName -> NonBlocking")
    }
  }
}

object FutureHandlerBlocking extends FutureHandler {

  private val duration = Duration(10, TimeUnit.SECONDS)

  override def WriteObject(future: Future[Object], onComplete: Boolean => Unit): Unit = {
    val written = try {
      Await.result(future, duration)
      true
    } catch {
      case exception: Exception =>
        exception.printStackTrace()
        false
    }
    onComplete(written)
  }

  override def GetOption[T](future: Future[Option[T]], onComplete: Option[T] => Unit): Unit = {
    val optionValue = try {
      Await.result(future, duration)
    } catch {
      case exception: Exception =>
        exception.printStackTrace()
        None
    }
    onComplete(optionValue)
  }

  override def GetSeq[T](future: Future[Seq[T]], onComplete: Seq[T] => Unit): Unit = {
    val seqValue = try {
      Await.result(future, duration)
    } catch {
      case exception: Exception =>
        exception.printStackTrace()
        Seq.empty[T]
    }
    onComplete(seqValue)
  }
}

object FutureHandlerNonBlocking extends FutureHandler {

  import scala.concurrent.ExecutionContext.Implicits.global

  override def WriteObject(future: Future[Object], onComplete: Boolean => Unit): Unit = {
    future.onComplete {
      case Success(_) =>
        onComplete(true)
      case Failure(exception) =>
        exception.printStackTrace()
        onComplete(false)
    }
  }

  override def GetOption[T](future: Future[Option[T]], onComplete: Option[T] => Unit): Unit = {
    future.onComplete {
      case Success(getOption) =>
        onComplete(getOption)
      case Failure(exception) =>
        exception.printStackTrace()
        onComplete(None)
    }
  }

  override def GetSeq[T](future: Future[Seq[T]], onComplete: Seq[T] => Unit): Unit = {
    future.onComplete {
      case Success(getSeq) =>
        onComplete(getSeq)
      case Failure(exception) =>
        exception.printStackTrace()
        onComplete(Seq.empty[T])
    }
  }
}
