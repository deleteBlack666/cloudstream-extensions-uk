package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class SearchModel (
    @SerializedName("results") val results : List<AnimeSearchResult>,
    @SerializedName("totalCount") val totalCount : Int? = null
)

data class AnimeSearchResult (
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("id") val id : Int,
    @SerializedName("image") val image : Image, // Клас Image вже має бути в іншому файлі
    @SerializedName("episodes") val episodes : Int? = null,
    @SerializedName("episodesAired") val episodesAired : Int? = null,
    @SerializedName("type") val type : String? = null
)
