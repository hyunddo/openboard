package org.dslul.openboard.inputmethod.latin.network;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class RagSearchResponse {
    @SerializedName("query_type")
    public String queryType;  // "info", "photo", "ambiguous"

    @SerializedName("answer")
    public String answer;     // info나 ambiguous일 때

    @SerializedName("info_results")
    public List<ResultItem> infoResults;   // info / ambiguous

    @SerializedName("photo_results")
    public List<ResultItem> photoResults;  // photo / ambiguous

    public static class ResultItem {
        @SerializedName("score") public double score;
        @SerializedName("id")    public String id;
        @SerializedName("text")  public String text;
    }
}
