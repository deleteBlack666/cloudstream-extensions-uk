package com.lagradost.models

import com.google.gson.annotations.SerializedName

data class SearchModel (
    @SerializedName("results") val results : List<Results>,
    @SerializedName("totalCount") val totalCount : Int? = null
)

data class Results (
    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("id") val id : Int,
    @SerializedName("image") val image : Image,
    @SerializedName("episodes") val episodes : Int? = null,
    @SerializedName("episodesAired") val episodesAired : Int? = null,
    @SerializedName("type") val type : String? = null, // Важливо для фільмів/серіалів
    @SerializedName("status") val status : String? = null
)
