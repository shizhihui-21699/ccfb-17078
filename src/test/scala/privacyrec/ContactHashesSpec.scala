package privacyrec

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class ContactHashesSpec extends AnyFunSuite with Matchers {
  test("phone normalization produces E.164-like digits for the demo") {
    ContactHashes.normalizePhone("138-0000-1234") shouldBe "+8613800001234"
    ContactHashes.normalizePhone("+86 138 0000 1234") shouldBe "+8613800001234"
  }

  test("HMAC contact hashes are keyed and deterministic") {
    val key = "demo-pepper".getBytes("UTF-8")
    val h1 = ContactHashes.hmacSha256(key, ContactHashes.normalizePhone("13800001234"))
    val h2 = ContactHashes.hmacSha256(key, ContactHashes.normalizePhone("138 0000 1234"))
    val otherKey = ContactHashes.hmacSha256("other".getBytes("UTF-8"), "+8613800001234")

    h1 shouldBe h2
    h1.value should not be otherKey.value
    h1.value should not include "13800001234"
  }

  test("keyed hashes carry a rotation key id") {
    val hasher = new KeyedContactHasher("k2026", "pepper".getBytes("UTF-8"))
    val hash = hasher.hashPhone("13800001234")

    hash.value should startWith("k2026:")
    hash.value should not include "13800001234"
    hasher.hashPhone("138 0000 1234") shouldBe hash
  }
}
