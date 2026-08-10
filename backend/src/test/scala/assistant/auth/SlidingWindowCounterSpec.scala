package assistant.auth

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit tests for the in-memory sliding-window rate-limit counter
  * (docs/authPlan.md §2, §7 step 12). `SlidingWindowCounter` was extracted
  * from the `@rateLimited` Cask decorator specifically so it can be tested
  * without standing up a web server.
  */
class SlidingWindowCounterSpec extends AnyFunSuite with Matchers {

  test("allows requests up to maxRequests within the window") {
    val counter = new SlidingWindowCounter(maxRequests = 3, windowMs = 60000L)
    counter.tryAcquire("1.2.3.4") shouldBe true
    counter.tryAcquire("1.2.3.4") shouldBe true
    counter.tryAcquire("1.2.3.4") shouldBe true
    counter.count("1.2.3.4") shouldBe 3
  }

  test("denies requests beyond maxRequests within the window") {
    val counter = new SlidingWindowCounter(maxRequests = 2, windowMs = 60000L)
    counter.tryAcquire("1.2.3.4") shouldBe true
    counter.tryAcquire("1.2.3.4") shouldBe true
    counter.tryAcquire("1.2.3.4") shouldBe false
    counter.count("1.2.3.4") shouldBe 2 // denied hits are not recorded
  }

  test("tracks different IPs independently") {
    val counter = new SlidingWindowCounter(maxRequests = 1, windowMs = 60000L)
    counter.tryAcquire("1.2.3.4") shouldBe true
    counter.tryAcquire("1.2.3.4") shouldBe false
    counter.tryAcquire("5.6.7.8") shouldBe true
    counter.tryAcquire("5.6.7.8") shouldBe false
    counter.count("1.2.3.4") shouldBe 1
    counter.count("5.6.7.8") shouldBe 1
  }

  test("allows requests again after the window slides past") {
    // 30ms window: small enough to wait out, large enough not to be flaky.
    val counter = new SlidingWindowCounter(maxRequests = 1, windowMs = 30L)
    counter.tryAcquire("1.2.3.4") shouldBe true
    counter.tryAcquire("1.2.3.4") shouldBe false
    Thread.sleep(60L)
    counter.tryAcquire("1.2.3.4") shouldBe true
  }

  test("rejects a non-positive maxRequests at construction") {
    an[IllegalArgumentException] should be thrownBy {
      new SlidingWindowCounter(maxRequests = 0, windowMs = 60000L)
    }
  }

  test("rejects a non-positive windowMs at construction") {
    an[IllegalArgumentException] should be thrownBy {
      new SlidingWindowCounter(maxRequests = 10, windowMs = 0L)
    }
  }
}
