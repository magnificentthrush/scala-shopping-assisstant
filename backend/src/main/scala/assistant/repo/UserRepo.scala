package assistant.repo

import assistant.domain.User
import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConverters._

class UserRepo {
  private val users = new ConcurrentHashMap[String, User]()

  def save(user: User): Unit = users.put(user.id, user)

  def findByEmail(email: String): Option[User] =
    users.values().asScala.find(_.email.equalsIgnoreCase(email))

  def findById(id: String): Option[User] = Option(users.get(id))

  def updateFullName(userId: String, fullName: String): Option[User] = {
    Option(users.computeIfPresent(userId, (_, u) => u.copy(fullName = fullName.trim)))
  }
}

object UserRepo {
  def apply(): UserRepo = new UserRepo()
}
