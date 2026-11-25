package privacyrec

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Contact identifiers should be normalized and keyed-hashed before they enter
 * bulk indexes. The pepper must live in a secret manager/KMS, not in source.
 *
 * HMAC rotates safely by key id; old and new hashes can be dual-written during
 * rotation. Raw phone numbers must never be logged or used as Map keys.
 */
object ContactHashes {
  private val Hex = "0123456789abcdef".toCharArray

  /** Demo normalization only. Production should use libphonenumber E.164. */
  def normalizePhone(raw: String, defaultCountryCode: String = "86"): String = {
    val digits = raw.replaceAll("[^0-9]", "")
    val withCountry =
      if (digits.startsWith("00")) digits.drop(2)
      else if (digits.length == 11 && digits.startsWith("1")) defaultCountryCode + digits
      else digits
    "+" + withCountry
  }

  def hmacSha256(pepper: Array[Byte], normalizedContact: String): PhoneHash = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(pepper, "HmacSHA256"))
    val bytes = mac.doFinal(normalizedContact.getBytes(StandardCharsets.UTF_8))
    PhoneHash(encodeHex(bytes))
  }

  /** Unkeyed SHA-256 is useful only for non-secret demo fixtures. */
  def sha256(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    encodeHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)))
  }

  private def encodeHex(bytes: Array[Byte]): String = {
    val out = new Array[Char](bytes.length * 2)
    var i = 0
    while (i < bytes.length) {
      val v = bytes(i) & 0xff
      out(i * 2) = Hex(v >>> 4)
      out(i * 2 + 1) = Hex(v & 0x0f)
      i += 1
    }
    new String(out)
  }
}

/**
 * Production hashes should carry a non-secret key version so rotation can
 * dual-write/backfill and later retire old peppers without ambiguous values.
 */
final class KeyedContactHasher(keyId: String, pepper: Array[Byte]) {
  require(keyId.nonEmpty && !keyId.contains(':'), "keyId must be non-empty and must not contain ':'")

  def hashNormalized(normalizedContact: String): PhoneHash = {
    val mac = ContactHashes.hmacSha256(pepper, normalizedContact)
    PhoneHash(s"$keyId:${mac.value}")
  }

  def hashPhone(rawPhone: String, defaultCountryCode: String = "86"): PhoneHash =
    hashNormalized(ContactHashes.normalizePhone(rawPhone, defaultCountryCode))
}
