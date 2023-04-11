package pt.isel.pc.problemsets.utils

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsBalancedMapTests {
    @Test
    fun `Check if received maps are in fifo order`() {
        val map = mapOf(
            1 to listOf(1, 2, 3, 4, 5),
            2 to listOf(6, 7, 8, 9, 10),
            3 to listOf(11, 12, 13, 14, 15),
            4 to listOf(16, 17, 18, 19, 20),
            5 to listOf(21, 22, 23, 24, 25)
        )
        assertTrue(isBalanced(map))
    }

    @Test
    fun `Check if received maps are not in fifo order`() {
        val map = mapOf(
            1 to listOf(1, 2, 3, 4, 5),
            2 to listOf(6, 7, 8, 9, 10),
            3 to listOf(11, 12, 13, 14, 15),
            4 to listOf(16, 17, 18, 19, 20),
            5 to listOf(21, 22, 23, 24, 25, 26)
        )
        assertFalse(isBalanced(map))
    }
}