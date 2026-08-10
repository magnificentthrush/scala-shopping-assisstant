package assistant.auth

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit tests for Argon2id hashing (docs/authPlan.md §5, §7 step 7).
  *
  * Real Argon2id hashing — ~150ms per hash, so this suite is small and
  * deliberately never touches the network. Verifies the round trip, the
  * per-hash salt, the Argon2id variant marker, and rejection of a wrong
  * password.
  */
class PasswordHasherSpec extends AnyFunSuite with Matchers {

  test("hash then verify succeeds for the correct password") {
    val hash = PasswordHasher.hash("correct1Horse")
    PasswordHasher.verify("correct1Horse", hash) shouldBe true
  }

  test("verify rejects a wrong password") {
    val hash = PasswordHasher.hash("correct1Horse")
    PasswordHasher.verify("wrongPassword1", hash) shouldBe false
  }

  test("verify rejects an empty password") {
    val hash = PasswordHasher.hash("correct1Horse")
    PasswordHasher.verify("", hash) shouldBe false
  }

  test("two hashes of the same password differ (per-hash salt)") {
    val a = PasswordHasher.hash("correct1Horse")
    val b = PasswordHasher.hash("correct1Horse")
    a should not equal b
    PasswordHasher.verify("correct1Horse", a) shouldBe true
    PasswordHasher.verify("correct1Horse", b) shouldBe true
  }

  test("hash uses the Argon2id variant marker") {
    PasswordHasher.hash("correct1Horse") should startWith("$argon2id$")
  }
}
