package ai.tinkered.jarvis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandRouterTest {
    @Test fun explicitCommands() {
        assertEquals(LocalCommand.TIME, CommandRouter.parse("Saat kaç?"))
        assertEquals(LocalCommand.DATE, CommandRouter.parse("Bugün hangi gün?"))
        assertEquals(LocalCommand.BATTERY, CommandRouter.parse("PİL DURUMU"))
        assertEquals(LocalCommand.HELP, CommandRouter.parse("YARDIM!"))
    }
    @Test fun handlesWhitespace() {
        assertEquals(LocalCommand.TIME, CommandRouter.parse("  saat   kaç ?  "))
        assertEquals(LocalCommand.BATTERY, CommandRouter.parse("pil\n yüzde\tkaç"))
    }
    @Test fun avoidsSubstringMatches() {
        assertNull(CommandRouter.parse("Saatler hakkında konuş"))
        assertNull(CommandRouter.parse("Tarih hakkında bir hikâye anlat"))
        assertNull(CommandRouter.parse("Pil teknolojisini açıkla"))
        assertNull(CommandRouter.parse("Bir saat sonra hatırlat"))
    }
    @Test fun rejectsEmptyAndUnknownText() {
        assertNull(CommandRouter.parse(""))
        assertNull(CommandRouter.parse("  ?!  "))
        assertNull(CommandRouter.parse("Merhaba JARVIS"))
    }
}
