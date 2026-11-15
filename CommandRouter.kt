package ai.tinkered.jarvis

import java.util.Locale

enum class LocalCommand { TIME, DATE, BATTERY, HELP }

/** Only explicit phrases trigger local commands; all other text remains a chat request. */
object CommandRouter {
    private val tr = Locale.forLanguageTag("tr-TR")
    fun parse(text: String): LocalCommand? {
        val command = text.lowercase(tr).trim().trimEnd('?', '.', '!').trim()
            .replace(Regex("\\s+"), " ")
        return when (command) {
            "saat", "saat kaç", "saat kaç oldu", "şu an saat kaç" -> LocalCommand.TIME
            "tarih", "bugünün tarihi", "bugünün tarihi ne", "bugün hangi gün", "hangi gün" -> LocalCommand.DATE
            "pil", "pil durumu", "pil kaç", "pil yüzde kaç", "şarj", "şarj durumu", "şarj kaç", "şarj yüzde kaç" -> LocalCommand.BATTERY
            "yardım" -> LocalCommand.HELP
            else -> null
        }
    }
}
