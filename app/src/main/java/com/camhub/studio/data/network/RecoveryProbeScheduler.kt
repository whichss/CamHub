package com.camhub.studio.data.network

/** Rotates once per evaluation, independently for each known shared network. */
internal class RecoveryProbeScheduler<K : Any> {
    private val lastChosen = mutableMapOf<K, String>()

    fun select(groups: Map<K, Collection<String>>): Map<K, String> {
        lastChosen.keys.retainAll(groups.keys)
        val selected = mutableMapOf<K, String>()
        for ((network, rawNames) in groups) {
            val names = rawNames.distinct().sorted()
            if (names.size < 2) {
                lastChosen.remove(network)
                continue
            }
            val last = lastChosen[network]
            val next = names.firstOrNull { last != null && it > last } ?: names.first()
            lastChosen[network] = next
            selected[network] = next
        }
        return selected
    }

    fun clear() = lastChosen.clear()
}
