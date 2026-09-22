package com.root.app.content

/** A catalog is untrusted input too, even though a manifest is verified again at installation. */
object CatalogValidation {
    fun requireValid(catalog: Catalog) {
        require(catalog.schemaVersion == 1 && catalog.catalogRevision > 0) { "Unsupported catalog version" }
        require(catalog.entries.map { it.id }.distinct().size == catalog.entries.size) { "Duplicate catalog pack ID" }
        catalog.entries.forEach { entry ->
            require(entry.id.length in 1..MAX_ID_LENGTH && ID_PATTERN.matches(entry.id)) {
                "Invalid catalog pack ID"
            }
            require(entry.version > 0) { "Invalid pack version" }
            require(entry.publication == PublicationStatus.PUBLISHED) { "Public catalog contains development content" }
            require(entry.title.isNotBlank() && entry.language.name.isNotBlank() &&
                LANGUAGE_CODE_PATTERN.matches(entry.language.code)) { "Invalid catalog language/title" }
            require(entry.unitCount >= 0 && entry.lessonCount >= 0 && entry.phraseCount >= 0 &&
                entry.audioCount in 0..ContentLimits.MAX_ASSETS_PER_PACK) { "Invalid catalog counts" }
            require(entry.manifestBytes in 1..ContentLimits.MANIFEST_MAX_BYTES &&
                entry.downloadBytes in entry.manifestBytes..ContentLimits.MAX_TOTAL_ASSET_BYTES + ContentLimits.MANIFEST_MAX_BYTES) { "Invalid catalog sizes" }
            require(entry.manifestSha256.matches(SHA256_HEX_PATTERN)) { "Invalid manifest checksum" }
            require(isSafeAssetKey(entry.manifestKey)) { "Invalid manifest key" }
        }
    }
}
