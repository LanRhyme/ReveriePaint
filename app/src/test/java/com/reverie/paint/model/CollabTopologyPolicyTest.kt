/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.reverie.paint.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollabTopologyPolicyTest {

    @Test
    fun `deriveVirtualIp maps peers deterministically into room 10_144 subnet`() {
        val ipHost = CollabTopologyPolicy.deriveVirtualIp("gw3dpm", "peer-host-1")
        val ipPeer = CollabTopologyPolicy.deriveVirtualIp("GW3DPM", "peer-guest-2")
        assertEquals(ipHost, CollabTopologyPolicy.deriveVirtualIp("GW3DPM", "peer-host-1"))
        assertTrue(ipHost.startsWith("10.144."))
        assertTrue(ipPeer.startsWith("10.144."))
        assertNotEquals(ipHost, ipPeer)

        val partsHost = ipHost.split(".")
        val partsPeer = ipPeer.split(".")
        assertEquals(4, partsHost.size)
        assertEquals(partsHost[2], partsPeer[2])
        assertTrue(partsHost[3].toInt() in 10..249)
        assertTrue(partsPeer[3].toInt() in 10..249)
    }

    @Test
    fun `deriveMeshPort produces valid high port deterministically`() {
        val port1 = CollabTopologyPolicy.deriveMeshPort("abc234")
        val port2 = CollabTopologyPolicy.deriveMeshPort("ABC234")
        assertEquals(port1, port2)
        assertTrue(port1 in 39000..48999)
    }

    @Test
    fun `parseNatType and natDifficultyLevel classify EasyTier NAT states accurately`() {
        assertEquals(CollabNatType.FULL_CONE, CollabTopologyPolicy.parseNatType("FullCone"))
        assertEquals(CollabNatType.PORT_RESTRICTED, CollabTopologyPolicy.parseNatType("PortRestricted"))
        assertEquals(CollabNatType.SYMMETRIC, CollabTopologyPolicy.parseNatType("Symmetric"))
        assertEquals(CollabNatType.UNKNOWN, CollabTopologyPolicy.parseNatType("Unexpected"))

        assertEquals(0, CollabTopologyPolicy.natDifficultyLevel(CollabNatType.UNKNOWN))
        assertEquals(1, CollabTopologyPolicy.natDifficultyLevel(CollabNatType.FULL_CONE))
        assertEquals(1, CollabTopologyPolicy.natDifficultyLevel(CollabNatType.RESTRICTED))
        assertEquals(2, CollabTopologyPolicy.natDifficultyLevel(CollabNatType.PORT_RESTRICTED))
        assertEquals(2, CollabTopologyPolicy.natDifficultyLevel(CollabNatType.SYMMETRIC_EASY_INC))
        assertEquals(3, CollabTopologyPolicy.natDifficultyLevel(CollabNatType.SYMMETRIC))
    }
}
