package cm.identishield.mobile.daily

import cm.identishield.mobile.data.Account
import cm.identishield.mobile.data.Holder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L-B4-2 (carte B6) : la requête automatique d'alertes tourne sur TOUS les écrans derrière une
 * session, et sur aucun écran d'avant (démarrage, non lié, verrou). Chaque variante est nommée :
 * un écran ajouté sans décision ici fait échouer le test.
 */
class DailyStepTest {
    private val holder = Holder("677001101", "677001101", "Aline MBALLA", true)
    private val account = Account("acc-000123", "677000123", "Hervé ESSOMBA", 12000, "TRUSTED", emptyList())

    /** Exhaustif : une variante ajoutée sans décision ici ne compile pas. */
    private fun decided(step: DailyStep): Boolean =
        when (step) {
            DailyStep.Starting, DailyStep.NotBound, is DailyStep.Unlock -> false
            DailyStep.Opening, is DailyStep.Home, is DailyStep.History, is DailyStep.Beneficiary,
            is DailyStep.Amount, is DailyStep.Confirm, is DailyStep.Authorize, is DailyStep.Sent,
            is DailyStep.Deferred, is DailyStep.Stopped,
            -> true
        }

    @Test
    fun everyScreenBehindASession_isPolled_andNoScreenBeforeItIs() {
        val steps =
            listOf(
                DailyStep.Starting,
                DailyStep.NotBound,
                DailyStep.Unlock("677000123"),
                DailyStep.Opening,
                DailyStep.Home(account, emptyList()),
                DailyStep.History(emptyList()),
                DailyStep.Beneficiary(emptyList()),
                DailyStep.Amount(holder, 12000),
                DailyStep.Confirm(holder, 5000),
                DailyStep.Authorize(holder, 5000),
                DailyStep.Sent(holder, 5000, "tr-1"),
                DailyStep.Deferred("tr-1", 5000, "677001101", "Aline MBALLA", null),
                DailyStep.Stopped(Outcome.REJECTED),
            )
        assertEquals(3, steps.count { !decided(it) })
        steps.forEach { step -> assertEquals(step.toString(), decided(step), step.isSignedIn()) }
    }
}
