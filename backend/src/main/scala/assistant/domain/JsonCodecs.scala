package assistant.domain

import upickle.default._

/** upickle `ReadWriter`s for domain types that get serialized to/from JSON
  * in more than one layer (`Filters` is stored as `jsonb` by the repo layer
  * AND sent to/read from Gemma by the LLM layer AND embedded in HTTP
  * responses). Defining it once here, in `domain`, means `repo`, `services`,
  * and `http` can all import it without any of them depending on each
  * other — `domain` sits at the bottom of the dependency graph and
  * everything else depends on it, never the other way around.
  */
object JsonCodecs {
  implicit val filtersRW: ReadWriter[Filters] = macroRW
}
