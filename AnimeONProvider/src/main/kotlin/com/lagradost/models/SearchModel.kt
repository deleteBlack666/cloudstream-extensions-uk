package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class SearchModel (
    @SerializedName("results") val results : List<AnimeSearchResult>,
)

data class AnimeSearchResult (
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("id") val id : Int,
    @SerializedName("image") val image : Image,
    @SerializedName("episodes") val episodes : Int? = null,
    @SerializedName("episodesAired") val episodesAired : Int? = null,
    @SerializedName("type") val type : String? = null
)
