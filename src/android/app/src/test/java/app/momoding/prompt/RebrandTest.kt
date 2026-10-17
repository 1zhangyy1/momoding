package app.momoding.prompt

import org.junit.Assert.assertEquals
import org.junit.Test

/** momoding: prose "Minis" becomes Momoding; command names and identifiers stay. */
class RebrandTest {
    @Test fun prose() = assertEquals("Read or change Momoding settings inside Momoding.", MomodingRules.rebrand("Read or change Minis settings inside Minis."))
    @Test fun commandsStay() = assertEquals("run minis-config, minis-open, MinisApp, Minis-x", MomodingRules.rebrand("run minis-config, minis-open, MinisApp, Minis-x"))
}
