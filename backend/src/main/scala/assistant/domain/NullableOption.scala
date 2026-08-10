package assistant.domain

import upickle.default._

/** upickle 3.x's built-in `Option[T]` codec represents a top-level `Some`/
  * `None` as a 0- or 1-element JSON array (`[]` / `[t]`) — and, worse,
  * reading a literal JSON `null` into an `Option[T]` field silently yields
  * a raw Scala `null` instead of `None`, which NPEs the moment anything
  * calls `.isEmpty` / `.map` on it (verified empirically; not documented
  * behavior). This was fixed in upickle 4.x ("unbox first level of
  * Options"), but this project is pinned to upickle 3.3.1 — a transitive
  * dependency of `cask` 0.9.2 (see `build.sbt` and `docs/authPlan.md` §2).
  *
  * Importing this object's implicit gives every domain case class the
  * upickle-4-style behavior instead: JSON `null` <-> `None`, and `Some(t)`
  * <-> the bare (unboxed) JSON value for `t`. That also matches how
  * Supabase's PostgREST represents nullable columns, which is exactly
  * what `User.verificationTokenHash` / `verificationTokenExpiresAt` read
  * from and write to.
  */
object NullableOption {
  implicit def nullableOptionRW[T](implicit inner: ReadWriter[T]): ReadWriter[Option[T]] =
    readwriter[ujson.Value].bimap[Option[T]](
      _.fold(ujson.Null: ujson.Value)(v => writeJs(v)(inner)),
      json => if (json.isNull) None else Some(read[T](json)(inner))
    )
}
