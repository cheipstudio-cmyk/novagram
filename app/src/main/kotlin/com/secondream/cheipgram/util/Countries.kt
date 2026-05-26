package com.secondream.cheipgram.util

/**
 * Static list of phone-dialling-code prefixes for the country picker on the
 * login screen. ISO codes follow ISO 3166-1 alpha-2; flag is the emoji
 * regional indicator pair so we don't have to ship per-flag PNGs. The list
 * is intentionally curated: it covers the ~120 countries Telegram is most
 * used in, sorted so we can show a sensible default near the top.
 */
data class Country(val iso: String, val name: String, val dialCode: String, val flag: String)

object Countries {
    val ALL: List<Country> = listOf(
        Country("IT", "Italia", "+39", "🇮🇹"),
        Country("US", "Stati Uniti", "+1", "🇺🇸"),
        Country("GB", "Regno Unito", "+44", "🇬🇧"),
        Country("DE", "Germania", "+49", "🇩🇪"),
        Country("FR", "Francia", "+33", "🇫🇷"),
        Country("ES", "Spagna", "+34", "🇪🇸"),
        Country("CH", "Svizzera", "+41", "🇨🇭"),
        Country("AT", "Austria", "+43", "🇦🇹"),
        Country("BE", "Belgio", "+32", "🇧🇪"),
        Country("NL", "Paesi Bassi", "+31", "🇳🇱"),
        Country("PT", "Portogallo", "+351", "🇵🇹"),
        Country("GR", "Grecia", "+30", "🇬🇷"),
        Country("IE", "Irlanda", "+353", "🇮🇪"),
        Country("DK", "Danimarca", "+45", "🇩🇰"),
        Country("SE", "Svezia", "+46", "🇸🇪"),
        Country("NO", "Norvegia", "+47", "🇳🇴"),
        Country("FI", "Finlandia", "+358", "🇫🇮"),
        Country("IS", "Islanda", "+354", "🇮🇸"),
        Country("PL", "Polonia", "+48", "🇵🇱"),
        Country("CZ", "Repubblica Ceca", "+420", "🇨🇿"),
        Country("SK", "Slovacchia", "+421", "🇸🇰"),
        Country("HU", "Ungheria", "+36", "🇭🇺"),
        Country("RO", "Romania", "+40", "🇷🇴"),
        Country("BG", "Bulgaria", "+359", "🇧🇬"),
        Country("HR", "Croazia", "+385", "🇭🇷"),
        Country("SI", "Slovenia", "+386", "🇸🇮"),
        Country("RS", "Serbia", "+381", "🇷🇸"),
        Country("BA", "Bosnia ed Erzegovina", "+387", "🇧🇦"),
        Country("ME", "Montenegro", "+382", "🇲🇪"),
        Country("MK", "Macedonia del Nord", "+389", "🇲🇰"),
        Country("AL", "Albania", "+355", "🇦🇱"),
        Country("MT", "Malta", "+356", "🇲🇹"),
        Country("CY", "Cipro", "+357", "🇨🇾"),
        Country("RU", "Russia", "+7", "🇷🇺"),
        Country("UA", "Ucraina", "+380", "🇺🇦"),
        Country("BY", "Bielorussia", "+375", "🇧🇾"),
        Country("MD", "Moldavia", "+373", "🇲🇩"),
        Country("LT", "Lituania", "+370", "🇱🇹"),
        Country("LV", "Lettonia", "+371", "🇱🇻"),
        Country("EE", "Estonia", "+372", "🇪🇪"),
        Country("TR", "Turchia", "+90", "🇹🇷"),
        Country("IL", "Israele", "+972", "🇮🇱"),
        Country("AE", "Emirati Arabi", "+971", "🇦🇪"),
        Country("SA", "Arabia Saudita", "+966", "🇸🇦"),
        Country("EG", "Egitto", "+20", "🇪🇬"),
        Country("MA", "Marocco", "+212", "🇲🇦"),
        Country("TN", "Tunisia", "+216", "🇹🇳"),
        Country("DZ", "Algeria", "+213", "🇩🇿"),
        Country("LY", "Libia", "+218", "🇱🇾"),
        Country("ZA", "Sudafrica", "+27", "🇿🇦"),
        Country("NG", "Nigeria", "+234", "🇳🇬"),
        Country("KE", "Kenya", "+254", "🇰🇪"),
        Country("ET", "Etiopia", "+251", "🇪🇹"),
        Country("GH", "Ghana", "+233", "🇬🇭"),
        Country("CN", "Cina", "+86", "🇨🇳"),
        Country("JP", "Giappone", "+81", "🇯🇵"),
        Country("KR", "Corea del Sud", "+82", "🇰🇷"),
        Country("IN", "India", "+91", "🇮🇳"),
        Country("PK", "Pakistan", "+92", "🇵🇰"),
        Country("BD", "Bangladesh", "+880", "🇧🇩"),
        Country("ID", "Indonesia", "+62", "🇮🇩"),
        Country("PH", "Filippine", "+63", "🇵🇭"),
        Country("TH", "Thailandia", "+66", "🇹🇭"),
        Country("VN", "Vietnam", "+84", "🇻🇳"),
        Country("MY", "Malesia", "+60", "🇲🇾"),
        Country("SG", "Singapore", "+65", "🇸🇬"),
        Country("HK", "Hong Kong", "+852", "🇭🇰"),
        Country("TW", "Taiwan", "+886", "🇹🇼"),
        Country("AU", "Australia", "+61", "🇦🇺"),
        Country("NZ", "Nuova Zelanda", "+64", "🇳🇿"),
        Country("CA", "Canada", "+1", "🇨🇦"),
        Country("MX", "Messico", "+52", "🇲🇽"),
        Country("BR", "Brasile", "+55", "🇧🇷"),
        Country("AR", "Argentina", "+54", "🇦🇷"),
        Country("CL", "Cile", "+56", "🇨🇱"),
        Country("CO", "Colombia", "+57", "🇨🇴"),
        Country("PE", "Perù", "+51", "🇵🇪"),
        Country("VE", "Venezuela", "+58", "🇻🇪"),
        Country("UY", "Uruguay", "+598", "🇺🇾"),
        Country("PY", "Paraguay", "+595", "🇵🇾"),
        Country("BO", "Bolivia", "+591", "🇧🇴"),
        Country("EC", "Ecuador", "+593", "🇪🇨"),
        Country("DO", "Repubblica Dominicana", "+1", "🇩🇴"),
        Country("CU", "Cuba", "+53", "🇨🇺"),
    )

    /** Default to Italy if we don't know better. */
    val DEFAULT: Country = ALL.first { it.iso == "IT" }

    fun find(iso: String): Country? = ALL.firstOrNull { it.iso.equals(iso, ignoreCase = true) }
}
