package cm.identishield.mobile.enrolment

import androidx.compose.ui.text.AnnotatedString
import org.junit.Assert.assertEquals
import org.junit.Test

/** D8 (B1b) : « 677000200 » s'affiche « 677 000 200 », la valeur reste brute, le curseur suit. */
class MsisdnTransformationTest {
    private fun transformed(raw: String) = MsisdnTransformation.filter(AnnotatedString(raw))

    @Test
    fun digitsAreGroupedByThree_onScreenOnly() {
        assertEquals("677 000 200", transformed("677000200").text.text)
        assertEquals("677 0", transformed("6770").text.text)
        assertEquals("67", transformed("67").text.text)
        assertEquals("", transformed("").text.text)
    }

    @Test
    fun cursorMapping_isConsistentInBothDirections() {
        val t = transformed("677000200")
        // original → affiché : avant chaque groupe entamé, une espace de plus.
        assertEquals(0, t.offsetMapping.originalToTransformed(0))
        assertEquals(3, t.offsetMapping.originalToTransformed(3))
        assertEquals(5, t.offsetMapping.originalToTransformed(4))
        assertEquals(11, t.offsetMapping.originalToTransformed(9))
        // affiché → original : une espace ne compte pas, jamais au-delà de la longueur brute.
        assertEquals(3, t.offsetMapping.transformedToOriginal(3))
        assertEquals(3, t.offsetMapping.transformedToOriginal(4))
        assertEquals(9, t.offsetMapping.transformedToOriginal(11))
        assertEquals(9, t.offsetMapping.transformedToOriginal(12))
        for (i in 0..9) {
            val back = t.offsetMapping.transformedToOriginal(t.offsetMapping.originalToTransformed(i))
            assertEquals("aller-retour pour $i", i, back)
        }
    }
}
