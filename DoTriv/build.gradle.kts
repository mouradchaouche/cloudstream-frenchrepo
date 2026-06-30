// DoTrivProvider/build.gradle

version = 1

cloudstream {
    language    = "fr"
    description = "Site de streaming français dotriv.com — films VF/VOSTFR en accès libre"
    authors = listOf("AMSC")

    /**
     * Status : 0 = OK | 1 = Lent | 2 = Beta | 3 = Cassé
     */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://dotriv.com/favicon.png"
}
dependencies {
    // Bibliothèques JSON nécessaires pour JsonProperty / Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
}
