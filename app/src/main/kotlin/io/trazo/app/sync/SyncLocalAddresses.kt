package io.trazo.app.sync

import io.trazo.core.sync.SyncAddresses
import java.net.NetworkInterface

/** Direcciones de este dispositivo en la red de casa o Tailscale (sin las de enlace local); IPv4 primero. */
internal fun localAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filter { SyncAddresses.isAllowed(it.address) && !it.isLinkLocalAddress }
        .sortedWith(compareBy({ if (SyncAddresses.isTailscale(it.address)) 1 else 0 }, { if (it.address.size == 4) 0 else 1 }))
        .mapNotNull { it.hostAddress?.substringBefore('%') }
        .distinct()
}.getOrDefault(emptyList())
