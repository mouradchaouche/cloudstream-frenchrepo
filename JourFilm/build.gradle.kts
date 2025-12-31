// use an integer for version numbers
version = 4


cloudstream {
    language = "fr"
    // All of these properties are optional, you can safely remove them

     description = "1 Jour 1 Film en plus d'être un site efficace et plaisant dispose d'un contenu visuel diversifié"
     authors = listOf("AMSC")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=french-stream.ac&sz=%size%"
}

dependencies {
    // Bibliothèques JSON nécessaires pour JsonProperty / Jackson
    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
}