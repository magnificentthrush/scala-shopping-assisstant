package assistant.auth

import de.mkammerer.argon2.Argon2Factory

/** Argon2id password hashing (docs/authPlan.md §5). A thin wrapper only —
  * `argon2-jvm` owns salting, encoding, and constant-time verification; no
  * custom crypto lives here.
  *
  * `Argon2Factory.create()` with no arguments defaults to the weaker
  * Argon2i variant, so the type is passed explicitly. Cost parameters
  * follow OWASP's 2023 Password Storage Cheat Sheet minimum for Argon2id:
  * >= 19 MiB memory, >= 2 iterations, 1 degree of parallelism.
  */
object PasswordHasher {
  private val Argon2Type = Argon2Factory.Argon2Types.ARGON2id
  private val MemoryCostKib = 19 * 1024 // 19 MiB
  private val Iterations = 2
  private val Parallelism = 1

  def hash(password: String): String = {
    val argon2 = Argon2Factory.create(Argon2Type) //create argon2 hasher of argon2id variant
    val chars = password.toCharArray    // string is immutable (so it remain in memorey, BAD) char[] are mutable and can be erased
    try {
      argon2.hash(Iterations, MemoryCostKib, Parallelism, chars) //return hash $argon2id$v=19$m=19456,t=2,p=1$...
    } finally {
      argon2.wipeArray(chars) //wipe the the plain text password from memory
    }
  }

  def verify(password: String, hash: String): Boolean = {
    val argon2 = Argon2Factory.create(Argon2Type)
    val chars = password.toCharArray
    try {
      argon2.verify(hash, chars)
    } finally {
      argon2.wipeArray(chars)
    }
  }
}
