package com.lagradost.models

import com.google.gson.annotations.SerializedName

class SearchModel (
    @SerializedName("result") val result: List<Result>? = null,
    @SerializedName("results") val results: List<Result>? = null,
)

data class Result (

    @SerializedName("titleUa") val titleUa : String,
    @SerializedName("id") val id : Int,
    @SerializedName("image") val image : Image,
    @SerializedName("episodes") val episodes : Int,
)
