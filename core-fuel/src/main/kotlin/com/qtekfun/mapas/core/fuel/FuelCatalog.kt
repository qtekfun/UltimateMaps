package com.qtekfun.mapas.core.fuel

/**
 * The fuels of the Spanish Ministry service (`Listados/ProductosPetroliferos/`), limited to those a driver can buy
 * at a station. `sourceProductId` is the service's `IDProducto` (verified against the live listing on 2026-10-07,
 * see docs/phase7/fuel-implementation.md); [FuelType.id] is our own stable key and never changes once shipped.
 *
 * Left out on purpose (the service lists them, a driver cannot fill a car with them): gasóleo C (heating, 7),
 * fuelóleos (9, 10), marine diesel (11), aviation fuels (12, 13, 14).
 */
object FuelTypes {
    val G95_E5 = FuelType("g95e5", "Gasolina 95 E5", 1)
    val G95_E10 = FuelType("g95e10", "Gasolina 95 E10", 23)
    val G95_E25 = FuelType("g95e25", "Gasolina 95 E25", 24)
    val G95_E85 = FuelType("g95e85", "Gasolina 95 E85", 25)
    val G95_E5_PREMIUM = FuelType("g95e5p", "Gasolina 95 E5 Premium", 20)
    val G98_E5 = FuelType("g98e5", "Gasolina 98 E5", 3)
    val G98_E10 = FuelType("g98e10", "Gasolina 98 E10", 21)
    val DIESEL_A = FuelType("goa", "Gasóleo A habitual", 4)
    val DIESEL_PREMIUM = FuelType("goap", "Gasóleo Premium", 5)
    val DIESEL_B = FuelType("gob", "Gasóleo B (agrícola)", 6)
    val BIOETHANOL = FuelType("bie", "Bioetanol", 16)
    val BIODIESEL = FuelType("bio", "Biodiésel", 8)
    val LPG = FuelType("glp", "GLP (autogás)", 17)
    val CNG = FuelType("gnc", "GNC (gas natural comprimido)", 18)
    val LNG = FuelType("gnl", "GNL (gas natural licuado)", 19)
    val HYDROGEN = FuelType("h2", "Hidrógeno", 22)
    val ADBLUE = FuelType("adblue", "AdBlue", 26)
    val RENEWABLE_DIESEL = FuelType("dren", "Diésel renovable", 27)
    val RENEWABLE_PETROL = FuelType("gren", "Gasolina renovable", 28)
    val METHANOL = FuelType("met", "Metanol", 29)
    val AMMONIA = FuelType("amo", "Amoniaco", 30)
    val BIOGAS_CNG = FuelType("bgnc", "Biogás comprimido", 31)
    val BIOGAS_LNG = FuelType("bgnl", "Biogás licuado", 32)

    /** In the order the settings screen shows them: the common ones first. */
    val all: List<FuelType> = listOf(
        G95_E5, G95_E10, G98_E5, G98_E10, DIESEL_A, DIESEL_PREMIUM, G95_E5_PREMIUM, LPG, CNG, LNG, HYDROGEN,
        G95_E85, G95_E25, DIESEL_B, BIODIESEL, BIOETHANOL, RENEWABLE_DIESEL, RENEWABLE_PETROL, BIOGAS_CNG, BIOGAS_LNG,
        ADBLUE, METHANOL, AMMONIA,
    )

    private val byId = all.associateBy { it.id }
    fun byId(id: String): FuelType? = byId[id]

    /**
     * Name of the price field in the national file (`EstacionesTerrestres/`, 23 `Precio ...` fields; irregular, as
     * the study of 2026-10-07 found: `Gasoleo A` without accent, `Diésel Renovable` with it). `FiltroProducto/{id}`
     * answers with a single `PrecioProducto` instead; this table is the fallback when a response comes in the
     * national shape.
     */
    private val nationalField = mapOf(
        "g95e5" to "Precio Gasolina 95 E5", "g95e10" to "Precio Gasolina 95 E10", "g95e25" to "Precio Gasolina 95 E25",
        "g95e85" to "Precio Gasolina 95 E85", "g95e5p" to "Precio Gasolina 95 E5 Premium", "g98e5" to "Precio Gasolina 98 E5",
        "g98e10" to "Precio Gasolina 98 E10", "goa" to "Precio Gasoleo A", "goap" to "Precio Gasoleo Premium",
        "gob" to "Precio Gasoleo B", "bie" to "Precio Bioetanol", "bio" to "Precio Biodiesel",
        "glp" to "Precio Gases licuados del petróleo", "gnc" to "Precio Gas Natural Comprimido",
        "gnl" to "Precio Gas Natural Licuado", "h2" to "Precio Hidrogeno", "adblue" to "Precio Adblue",
        "dren" to "Precio Diésel Renovable", "gren" to "Precio Gasolina Renovable", "met" to "Precio Metanol",
        "amo" to "Precio Amoniaco", "bgnc" to "Precio Biogas Natural Comprimido", "bgnl" to "Precio Biogas Natural Licuado",
    )

    fun nationalField(id: String): String? = nationalField[id]
}
