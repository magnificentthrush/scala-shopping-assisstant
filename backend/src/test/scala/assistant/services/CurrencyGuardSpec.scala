package assistant.services

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CurrencyGuardSpec extends AnyFunSuite with Matchers {

  test("a plausible dollar budget for the category converts to INR at the fixed rate") {
    CurrencyGuard.resolve("shirt under $10", Some("shirt")) shouldBe CurrencyGuard.Convert(BigDecimal(830))
  }

  test("an implausible dollar budget for the category is flagged for clarification") {
    CurrencyGuard.resolve("shirt under $1000", Some("shirt")) shouldBe
      CurrencyGuard.Clarify(BigDecimal(1000), BigDecimal(83000))
  }

  test("the same dollar figure is plausible under a category with a higher ceiling") {
    CurrencyGuard.resolve("laptop under $1000", Some("laptop")) shouldBe CurrencyGuard.Convert(BigDecimal(83000))
  }

  test("a bare number with no currency symbol or word is left untouched") {
    CurrencyGuard.resolve("under 1000", Some("shirt")) shouldBe CurrencyGuard.NoDollarAmount
  }

  test("'dollars' word form is parsed the same as the $ symbol") {
    CurrencyGuard.resolve("budget is 100 dollars", None) shouldBe CurrencyGuard.Convert(BigDecimal(8300))
  }

  test("comma-grouped dollar amounts parse correctly") {
    CurrencyGuard.resolve("looking for something around $1,000", None) shouldBe
      CurrencyGuard.Clarify(BigDecimal(1000), BigDecimal(83000))
  }

  test("missing category falls back to the default ceiling") {
    // $400 is exactly the default ceiling -> still convertible.
    CurrencyGuard.resolve("$400 gift", None) shouldBe CurrencyGuard.Convert(BigDecimal(33200))
    // $401 tips over the default ceiling -> clarify.
    CurrencyGuard.resolve("$401 gift", None) shouldBe CurrencyGuard.Clarify(BigDecimal(401), BigDecimal(33283))
  }

  test("'bucks' word form is recognized") {
    CurrencyGuard.resolve("got about 50 bucks to spend", None) shouldBe CurrencyGuard.Convert(BigDecimal(4150))
  }

  test("'usd' word form is recognized") {
    CurrencyGuard.resolve("budget 200 usd", None) shouldBe CurrencyGuard.Convert(BigDecimal(16600))
  }

  test("category matching is case-insensitive with substring tolerance") {
    CurrencyGuard.resolve("t-shirt under $200", Some("T-Shirts")) shouldBe
      CurrencyGuard.Clarify(BigDecimal(200), BigDecimal(16600))
    CurrencyGuard.resolve("t-shirt under $100", Some("T-Shirts")) shouldBe CurrencyGuard.Convert(BigDecimal(8300))
  }
}
