// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.sellby.data

// Ported verbatim from sellby_keyboard.dart project's OngkirData.expeditions (ongkir_panel.dart)
// - acuan tunggal. This is a compile-time constant catalog, NOT user data - only which ids are
// enabled is persisted (see entity/Expedition.kt). iconName matches the courier's SVG basename
// under design-assets/icons/expeditions/, converted to drawable ic_expedition_<iconName>.
data class ExpeditionCatalogItem(
    val id: String,
    val displayName: String,
    val iconName: String,
    val webUrl: String,
    val isAppDelivery: Boolean = false,
    val appScheme: String? = null,
    val appPackageName: String? = null,
)

object ExpeditionCatalog {
    /** Ids removed from [all] below (Rara Delivery, JDL Express - deemed unlikely to ever be
     *  used) - kept here so callers can clean up any already-seeded Room rows for them via
     *  ExpeditionDao.deleteByIds(), rather than leaving orphaned entries in the database. */
    val REMOVED_IDS: List<String> = listOf("rara", "jdl")

    val all: List<ExpeditionCatalogItem> = listOf(
        ExpeditionCatalogItem("jnt", "J&T Express", "jnt", "https://biteship.com/id/cek-ongkir/jnt"),
        ExpeditionCatalogItem("grab", "GrabExpress", "grab", "https://www.grab.com/id/express/",
            isAppDelivery = true, appScheme = "grab://open?screenType=EXPRESS", appPackageName = "com.grabtaxi.passenger"),
        ExpeditionCatalogItem("pos", "POS Indonesia", "pos", "https://biteship.com/id/cek-ongkir/pos"),
        ExpeditionCatalogItem("lalamove", "Lalamove", "lalamove", "https://www.lalamove.com/id-id/",
            isAppDelivery = true, appScheme = "lalamove://", appPackageName = "hk.easyvan.app.client"),
        ExpeditionCatalogItem("tiki", "Tiki", "tiki", "https://biteship.com/id/cek-ongkir/tiki"),
        ExpeditionCatalogItem("gosend", "GoSend", "gosend", "https://www.gojek.com/id-id/layanan/gosend/",
            isAppDelivery = true, appScheme = "gojek://gosend", appPackageName = "com.gojek.app"),
        ExpeditionCatalogItem("anteraja", "AnterAja", "anteraja", "https://biteship.com/id/cek-ongkir/anteraja",
            isAppDelivery = true, appScheme = "anteraja://", appPackageName = "id.anteraja.app"),
        ExpeditionCatalogItem("paxel", "Paxel", "paxel", "https://paxel.co/id/check-rates",
            isAppDelivery = true, appScheme = "paxel://", appPackageName = "co.paxel"),
        ExpeditionCatalogItem("jne", "JNE", "jne", "https://biteship.com/id/cek-ongkir/jne"),
        ExpeditionCatalogItem("sicepat", "SiCepat Ekspres", "sicepat", "https://biteship.com/id/cek-ongkir/sicepat"),
        ExpeditionCatalogItem("sap", "SAP Express", "sap", "https://biteship.com/id/cek-ongkir/sap"),
        ExpeditionCatalogItem("ninja", "Ninja Xpress", "ninja", "https://biteship.com/id/cek-ongkir/ninja"),
        ExpeditionCatalogItem("wahana", "Wahana Express", "wahana", "https://biteship.com/id/cek-ongkir/wahana"),
        ExpeditionCatalogItem("lion", "Lion Parcel", "lion", "https://biteship.com/id/cek-ongkir/lion"),
        ExpeditionCatalogItem("idexpress", "IDexpress", "idexpress", "https://biteship.com/id/cek-ongkir/idexpress"),
        ExpeditionCatalogItem("deliveree", "Deliveree", "deliveree", "https://www.deliveree.com/id/price-calculator/",
            isAppDelivery = true, appScheme = "deliveree://", appPackageName = "com.deliveree.client"),
        ExpeditionCatalogItem("superkul", "Superkul", "superkul", "https://superkul.id/"),
        ExpeditionCatalogItem("mrspeedy", "Mr Speedy (Borzo)", "mrspeedy", "https://biteship.com/id/cek-ongkir/mrspeedy"),
        ExpeditionCatalogItem("rpx", "RPX", "rpx", "https://biteship.com/id/cek-ongkir/rpx"),
        ExpeditionCatalogItem("jet", "JET Express", "jet", "https://biteship.com/id/cek-ongkir/jet"),
        ExpeditionCatalogItem("pribadi", "Kurir Pribadi", "pribadi", "https://google.com"),
    )
}
