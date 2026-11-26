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

    @Test
    fun `normalizeRelayUris 丢弃旧 TURN 与 STUN 配置并为裸地址补 tcp 前缀`() {
        val out = CollabTopologyPolicy.normalizeRelayUris(
            listOf(
                " turn:turn.example.com:3478 ",
                "stuns:stun.example.com",
                "relay.example.com:11010",
                "tcp://relay.example.com:11010",
                "udp://relay.example.com:11010",
                "",
                "   ",
            ),
        )
        assertEquals(
            listOf("tcp://relay.example.com:11010", "udp://relay.example.com:11010"),
            out,
        )
    }

    @Test
    fun `normalizeRelayUris 关闭 KCP 加速时剔除 udp 节点并保序去重`() {
        val out = CollabTopologyPolicy.normalizeRelayUris(
            listOf(
                "tcp://a.example.com:11010",
                "UDP://b.example.com:11010",
                "udp://c.example.com:11010",
                "tcp://a.example.com:11010",
            ),
            kcpProxyEnabled = false,
        )
        assertEquals(listOf("tcp://a.example.com:11010"), out)
    }

    @Test
    fun `normalizeRelayUris 只剩 udp 且关闭 KCP 时返回空, 由兜底节点接管`() {
        assertTrue(CollabTopologyPolicy.normalizeRelayUris(listOf("udp://only.example.com:11010"), false).isEmpty())
        assertEquals(
            listOf(CollabTopologyPolicy.FALLBACK_RELAY_URI),
            CollabTopologyPolicy.normalizeRelayUris(listOf(CollabTopologyPolicy.FALLBACK_RELAY_URI), false),
        )
        assertTrue(CollabTopologyPolicy.FALLBACK_RELAY_URI.startsWith("tcp://"))
    }
}
