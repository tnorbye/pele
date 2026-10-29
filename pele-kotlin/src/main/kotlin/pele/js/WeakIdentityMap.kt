// PORT: JVM-only helper with no upstream counterpart (see UPDATE.md).
package pele.js

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * A `WeakMap`: keys are compared by identity and held weakly. A value must not reference its own
 * key, or the entry is never collected.
 */
class WeakIdentityMap<K : Any, V : Any> {
  private class Key(referent: Any, q: ReferenceQueue<Any>?) : WeakReference<Any>(referent, q) {
    val hash = System.identityHashCode(referent)

    override fun hashCode() = hash

    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (other !is Key || other.hash != hash) return false
      val r = get()
      return r != null && r === other.get()
    }
  }

  private val queue = ReferenceQueue<Any>()
  private val map = HashMap<Key, V>()

  private fun expunge() {
    while (true) map.remove(queue.poll() ?: return)
  }

  @Synchronized
  operator fun get(key: K): V? {
    expunge()
    return map[Key(key, null)]
  }

  @Synchronized
  operator fun set(key: K, value: V) {
    expunge()
    map[Key(key, queue)] = value
  }

  @Synchronized
  fun has(key: K): Boolean {
    expunge()
    return map.containsKey(Key(key, null))
  }

  @Synchronized
  fun delete(key: K): Boolean {
    expunge()
    return map.remove(Key(key, null)) != null
  }
}
