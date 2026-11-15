package com.hermes.chatandroid

import com.hermes.chatandroid.net.AttachmentImageStore
import com.hermes.chatandroid.net.BridgeApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentImageStoreTest {
    private class FakeBridge : BridgeApi("http://x", "") {
        val asked = mutableListOf<String>()
        override suspend fun fetchRaw(relativePath: String): ByteArray {
            asked += relativePath
            return byteArrayOf(1, 2, 3)
        }
    }

    @Test
    fun relativePath_isRelativeToHermesHome() {
        assertEquals("uploads/a.jpg", AttachmentImageStore.relativePath("/Users/macmini/.hermes/uploads/a.jpg"))
        assertEquals(
            "profiles/coder/uploads/b.png",
            AttachmentImageStore.relativePath("/Users/macmini/.hermes/profiles/coder/uploads/b.png"),
        )
    }

    @Test
    fun relativePath_outsideHermesHomeIsNull() {
        // Hermes-bot 로컬 폴백 업로드(data/uploads) 는 Bridge 로 받을 수 없다.
        assertNull(AttachmentImageStore.relativePath("/Users/macmini/orca/Hermes-bot/data/uploads/default/a.jpg"))
        assertNull(AttachmentImageStore.relativePath("/Users/macmini/.hermes/"))
    }

    @Test
    fun load_fetchesOnceThenServesFromMemory() = runTest {
        val bridge = FakeBridge()
        val store = AttachmentImageStore({ bridge })
        val path = "/Users/macmini/.hermes/uploads/a.jpg"
        assertArrayEquals(byteArrayOf(1, 2, 3), store.load(path))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.load(path))
        assertEquals(listOf("uploads/a.jpg"), bridge.asked)
    }

    @Test
    fun load_usesBytesPutByUploaderWithoutNetwork() = runTest {
        val bridge = FakeBridge()
        val store = AttachmentImageStore({ bridge })
        store.put("/Users/macmini/.hermes/uploads/g.jpg", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), store.load("/Users/macmini/.hermes/uploads/g.jpg"))
        assertEquals(emptyList<String>(), bridge.asked)
    }

    @Test
    fun load_unreachablePathIsNull() = runTest {
        val bridge = FakeBridge()
        assertNull(AttachmentImageStore({ bridge }).load("/tmp/elsewhere/a.jpg"))
        assertNull(AttachmentImageStore({ null }).load("/Users/macmini/.hermes/uploads/a.jpg"))
    }

    @Test
    fun put_evictsLeastRecentlyUsedOverBudget() = runTest {
        val bridge = FakeBridge()
        val store = AttachmentImageStore({ bridge }, maxBytes = 4)
        store.put("/h/.hermes/uploads/a", byteArrayOf(1, 1))
        store.put("/h/.hermes/uploads/b", byteArrayOf(2, 2))
        store.load("/h/.hermes/uploads/a")              // a 를 최근 사용으로
        store.put("/h/.hermes/uploads/c", byteArrayOf(3, 3))   // 한도 초과 → b 가 밀려난다
        store.load("/h/.hermes/uploads/a")
        store.load("/h/.hermes/uploads/b")
        assertEquals(listOf("uploads/b"), bridge.asked)
    }
}
